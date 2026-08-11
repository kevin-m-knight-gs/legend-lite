package org.finos.legend.engine.test;

import org.finos.legend.engine.nlq.SemanticIndex;
import org.finos.legend.engine.nlq.ModelSchemaExtractor;
import com.legend.model.ClassDefinition;
import com.legend.model.ParsedModel;
import org.finos.legend.engine.nlq.NlqModel;
import com.gs.legend.model.m3.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the ISDA CDM Pure model.
 * Validates the auto-generated model loads, indexes, and can be queried.
 */
@DisplayName("NLQ ISDA CDM Model — Smoke Tests")
class NlqCdmModelTest {

    private static ParsedModel modelBuilder;
    private static com.legend.compiler.element.ModelContext compiled;
    private static SemanticIndex index;

    @BeforeAll
    static void setup() throws IOException {
        String pureSource;
        try (InputStream is = NlqCdmModelTest.class.getResourceAsStream("/nlq/cdm-model.pure")) {
            assertNotNull(is, "CDM model resource not found");
            pureSource = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        modelBuilder = NlqModel.parse(pureSource);
        compiled = com.legend.Compiler.compileModel(pureSource);

        index = new SemanticIndex();
        index.buildIndex(modelBuilder);
    }

    @Test
    @DisplayName("Model loads 700+ classes")
    void testClassCount() {
        Map<String, ClassDefinition> allClasses = NlqModel.allClasses(modelBuilder);
        System.out.println("CDM classes loaded: " + allClasses.size());
        assertTrue(allClasses.size() >= 700,
                "Expected at least 700 classes, got " + allClasses.size());
    }

    @Test
    @DisplayName("Semantic index is populated")
    void testIndexSize() {
        System.out.println("CDM index entries: " + index.size());
        assertTrue(index.size() >= 700,
                "Expected at least 700 indexed entries, got " + index.size());
    }

    @Test
    @DisplayName("Key product classes exist")
    void testProductClasses() {
        assertNotNull(NlqModel.allClasses(modelBuilder).get("template::EconomicTerms"), "EconomicTerms missing");
        assertNotNull(NlqModel.allClasses(modelBuilder).get("template::TransferableProduct"), "TransferableProduct missing");
        assertNotNull(NlqModel.allClasses(modelBuilder).get("product::InterestRatePayout"), "InterestRatePayout missing");
    }

    @Test
    @DisplayName("Key event classes exist")
    void testEventClasses() {
        assertNotNull(NlqModel.allClasses(modelBuilder).get("event::BusinessEvent"), "BusinessEvent missing");
        assertNotNull(NlqModel.allClasses(modelBuilder).get("event::TradeState"), "TradeState missing");
        assertNotNull(NlqModel.allClasses(modelBuilder).get("event::Trade"), "Trade missing");
    }

    @Test
    @DisplayName("Key party/asset classes exist")
    void testRefDataClasses() {
        assertNotNull(NlqModel.allClasses(modelBuilder).get("party::Party"), "Party missing");
        assertNotNull(NlqModel.allClasses(modelBuilder).get("asset::AssetIdentifier"), "AssetIdentifier missing");
    }

    @Test
    @DisplayName("Retrieval: interest rate swap query")
    void testRetrievalSwap() {
        List<SemanticIndex.RetrievalResult> results = index.retrieve("interest rate swap payout fixed floating", 15, null);
        List<String> classNames = results.stream()
                .map(SemanticIndex.RetrievalResult::qualifiedName)
                .toList();
        System.out.println("  'interest rate swap' → " + classNames);
        assertTrue(classNames.stream().anyMatch(n ->
                        n.contains("InterestRate") || n.contains("Payout") || n.contains("Swap")),
                "Expected IR/swap class, got: " + classNames);
    }

    @Test
    @DisplayName("Retrieval: trade lifecycle event query")
    void testRetrievalEvent() {
        List<SemanticIndex.RetrievalResult> results = index.retrieve("trade execution business event lifecycle", 15, null);
        List<String> classNames = results.stream()
                .map(SemanticIndex.RetrievalResult::qualifiedName)
                .toList();
        System.out.println("  'trade event' → " + classNames);
        assertTrue(classNames.stream().anyMatch(n ->
                        n.contains("Trade") || n.contains("Event") || n.contains("Execution")),
                "Expected trade/event class, got: " + classNames);
    }

    @Test
    @DisplayName("Retrieval: collateral query")
    void testRetrievalCollateral() {
        List<SemanticIndex.RetrievalResult> results = index.retrieve("collateral eligibility criteria", 15, null);
        List<String> classNames = results.stream()
                .map(SemanticIndex.RetrievalResult::qualifiedName)
                .toList();
        System.out.println("  'collateral' → " + classNames);
        assertTrue(classNames.stream().anyMatch(n ->
                        n.contains("Collateral") || n.contains("Eligib") || n.contains("collateral")),
                "Expected collateral class, got: " + classNames);
    }

    @Test
    @DisplayName("Retrieval: settlement query")
    void testRetrievalSettlement() {
        List<SemanticIndex.RetrievalResult> results = index.retrieve("cash settlement physical delivery instructions", 15, null);
        List<String> classNames = results.stream()
                .map(SemanticIndex.RetrievalResult::qualifiedName)
                .toList();
        System.out.println("  'settlement' → " + classNames);
        assertTrue(classNames.stream().anyMatch(n ->
                        n.contains("Settlement") || n.contains("Delivery") || n.contains("Transfer")),
                "Expected settlement class, got: " + classNames);
    }

    @Test
    @DisplayName("Schema extraction works for CDM model")
    void testSchemaExtraction() {
        Set<String> classNames = Set.of(
                "event::Trade",
                "event::BusinessEvent",
                "template::EconomicTerms"
        );
        String schema = ModelSchemaExtractor.extractSchema(classNames, modelBuilder);
        assertNotNull(schema);
        assertFalse(schema.isEmpty());
        assertTrue(schema.contains("Trade"), "Schema should contain Trade");
        assertTrue(schema.contains("EconomicTerms"), "Schema should contain EconomicTerms");
        System.out.println("Schema length: " + schema.length() + " chars");
        System.out.println("Schema preview:\n" + schema.substring(0, Math.min(500, schema.length())));
    }

    @Test
    @DisplayName("Model has 250+ enum definitions")
    void testEnumCount() {
        Map<String, com.legend.model.EnumDefinition> allEnums = NlqModel.allEnums(modelBuilder);
        System.out.println("CDM enums loaded: " + allEnums.size());
        assertTrue(allEnums.size() >= 250,
                "Expected at least 250 enums, got " + allEnums.size());
    }

    @Test
    @DisplayName("Key enums exist with correct values")
    void testKeyEnums() {
        var actionEnum = compiled.findEnum("event::ActionEnum").orElse(null);
        assertNotNull(actionEnum, "ActionEnum missing");
        assertTrue(actionEnum.values().contains("New"), "ActionEnum should have 'New'");
        assertTrue(actionEnum.values().contains("Cancel"), "ActionEnum should have 'Cancel'");

        var creditEventType = compiled.findEnum("event::CreditEventTypeEnum").orElse(null);
        assertNotNull(creditEventType, "CreditEventTypeEnum missing");
        assertTrue(creditEventType.values().contains("Bankruptcy"), "CreditEventTypeEnum should have 'Bankruptcy'");
    }

    @Test
    @DisplayName("Model has 1100+ associations")
    void testAssociationCount() {
        int assocCount = NlqModel.allAssociations(modelBuilder).size();
        System.out.println("CDM associations loaded: " + assocCount);
        assertTrue(assocCount >= 1100,
                "Expected at least 1100 associations, got " + assocCount);
    }

    @Test
    @DisplayName("Properties are correctly typed (no class refs on class body)")
    void testPropertyTypes() {
        int enumProps = 0, primitiveProps = 0, classProps = 0;
        for (String fqn : NlqModel.allClasses(modelBuilder).keySet()) {
            var tc = compiled.findClass(fqn).orElseThrow();
            for (var p : tc.properties()) {
                var t = p.type();
                if (t instanceof com.legend.compiler.element.type.Type.EnumType) enumProps++;
                else if (t instanceof com.legend.compiler.element.type.Type.Primitive) primitiveProps++;
                else if (t instanceof com.legend.compiler.element.type.Type.ClassType) classProps++;
            }
        }
        System.out.printf("Property types — primitive: %d, enum: %d, class: %d%n",
                primitiveProps, enumProps, classProps);
        assertEquals(0, classProps,
                "Class body should have no class-typed properties (handled by associations)");
        assertTrue(enumProps > 0, "Should have enum-typed properties");
    }

    @Test
    @DisplayName("CreditEvent has enum-typed creditEventType property")
    void testEnumTypedProperty() {
        var cet = compiled.findProperty("event::CreditEvent", "creditEventType")
                .orElse(null);
        assertNotNull(cet, "creditEventType property missing");
        assertInstanceOf(com.legend.compiler.element.type.Type.EnumType.class, cet.type(),
                "creditEventType should be an EnumType, got: " + cet.type().getClass().getSimpleName());
        // Simple name matches the pre-flag-day assertEquals("CreditEventTypeEnum", typeName()) exactly.
        assertEquals("CreditEventTypeEnum", NlqModel.simpleName(
                ((com.legend.compiler.element.type.Type.EnumType) cet.type()).fqn()));
    }

    @Test
    @DisplayName("Model statistics summary")
    void testModelStats() {
        Map<String, ClassDefinition> allClasses = NlqModel.allClasses(modelBuilder);
        int totalProps = allClasses.values().stream()
                .mapToInt(c -> c.properties().size())
                .sum();

        Set<String> domains = new HashSet<>();
        for (String name : allClasses.keySet()) {
            if (name.contains("::")) {
                domains.add(name.substring(0, name.indexOf("::")));
            }
        }

        System.out.println("\n═══════════════════════════════════════════════════");
        System.out.println("  ISDA CDM Pure Model Statistics");
        System.out.println("═══════════════════════════════════════════════════");
        System.out.printf("  Enums:        %d%n", NlqModel.allEnums(modelBuilder).size());
        System.out.printf("  Classes:      %d%n", allClasses.size());
        System.out.printf("  Properties:   %d%n", totalProps);
        System.out.printf("  Associations: %d%n", NlqModel.allAssociations(modelBuilder).size());
        System.out.printf("  Domains:      %d (%s)%n", domains.size(), domains);
        System.out.printf("  Index entries: %d%n", index.size());
        System.out.println("═══════════════════════════════════════════════════");
    }
}
