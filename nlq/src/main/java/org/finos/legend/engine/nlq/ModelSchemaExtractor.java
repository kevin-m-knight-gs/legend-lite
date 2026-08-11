package org.finos.legend.engine.nlq;

import com.legend.model.AssociationDefinition;
import com.legend.model.ClassDefinition;
import com.legend.model.ParsedModel;

import java.util.*;

/**
 * Extracts a focused model schema from the top-K retrieved classes,
 * formatted as compact text optimized for LLM context windows.
 *
 * Target: ~2-4K tokens regardless of total model size.
 */
public class ModelSchemaExtractor {

    /**
     * Builds a focused schema string from a set of relevant class names.
     * Includes full property details for the primary classes and reduced
     * detail (name + description only) for 1-hop associated classes.
     *
     * @param classNames   The set of relevant class qualified or simple names
     * @param modelBuilder The model builder with the full model
     * @return A compact text schema suitable for LLM context
     */
    public static String extractSchema(Set<String> classNames, ParsedModel model) {
        Map<String, ClassDefinition> allClasses = NlqModel.allClasses(model);
        Map<String, AssociationDefinition> allAssociations = NlqModel.allAssociations(model);

        // Resolve class names to PureClass objects
        Map<String, ClassDefinition> primaryClasses = new LinkedHashMap<>();
        for (String name : classNames) {
            ClassDefinition pc = allClasses.get(name);
            if (pc == null) {
                // Try lookup by simple name
                for (ClassDefinition candidate : allClasses.values()) {
                    if (NlqModel.simpleName(candidate.qualifiedName()).equals(name)) {
                        pc = candidate;
                        break;
                    }
                }
            }
            if (pc != null) {
                primaryClasses.put(pc.qualifiedName(), pc);
            }
        }

        // Find associations between primary classes
        List<AssociationDefinition> relevantAssociations = new ArrayList<>();
        Set<String> primaryNames = new HashSet<>();
        for (ClassDefinition pc : primaryClasses.values()) {
            primaryNames.add(pc.qualifiedName());
            primaryNames.add(NlqModel.simpleName(pc.qualifiedName()));
        }

        for (AssociationDefinition assoc : allAssociations.values()) {
            String t1 = NlqModel.typeName(assoc.property1().targetClass());
            String t2 = NlqModel.typeName(assoc.property2().targetClass());
            if (matchesAny(t1, primaryNames) || matchesAny(t2, primaryNames)) {
                relevantAssociations.add(assoc);
            }
        }

        // Find 1-hop neighbors (reduced detail)
        Set<String> neighborNames = new HashSet<>();
        for (AssociationDefinition assoc : relevantAssociations) {
            String t1 = NlqModel.typeName(assoc.property1().targetClass());
            String t2 = NlqModel.typeName(assoc.property2().targetClass());
            if (!matchesAny(t1, primaryNames)) neighborNames.add(t1);
            if (!matchesAny(t2, primaryNames)) neighborNames.add(t2);
        }

        Map<String, ClassDefinition> neighborClasses = new LinkedHashMap<>();
        for (String name : neighborNames) {
            ClassDefinition pc = allClasses.get(name);
            if (pc == null) {
                for (ClassDefinition candidate : allClasses.values()) {
                    if (NlqModel.simpleName(candidate.qualifiedName()).equals(name)) {
                        pc = candidate;
                        break;
                    }
                }
            }
            if (pc != null && !primaryClasses.containsKey(pc.qualifiedName())) {
                neighborClasses.put(pc.qualifiedName(), pc);
            }
        }

        // Build the schema text
        StringBuilder sb = new StringBuilder();


        // Primary classes with full detail
        sb.append("=== Classes (full detail) ===\n\n");
        for (ClassDefinition pc : primaryClasses.values()) {
            appendClassFull(sb, pc, allClasses);
        }

        // Neighbor classes with reduced detail
        if (!neighborClasses.isEmpty()) {
            sb.append("=== Related Classes (summary) ===\n\n");
            for (ClassDefinition pc : neighborClasses.values()) {
                appendClassSummary(sb, pc, allClasses);
            }
        }

        // Associations
        if (!relevantAssociations.isEmpty()) {
            sb.append("=== Associations ===\n\n");
            for (AssociationDefinition assoc : relevantAssociations) {
                appendAssociation(sb, assoc);
            }
        }

        return sb.toString();
    }

    /**
     * Builds a primary-only schema for the router: full class detail for each candidate
     * plus a compact "Associations:" line per class listing connected class names.
     * No neighbor summaries or full association blocks — just what the router needs to pick.
     */
    public static String extractPrimarySchema(Set<String> classNames, ParsedModel model) {
        Map<String, ClassDefinition> allClasses = NlqModel.allClasses(model);
        Map<String, AssociationDefinition> allAssociations = NlqModel.allAssociations(model);

        // Resolve class names to PureClass objects
        Map<String, ClassDefinition> primaryClasses = new LinkedHashMap<>();
        for (String name : classNames) {
            ClassDefinition pc = allClasses.get(name);
            if (pc == null) {
                for (ClassDefinition candidate : allClasses.values()) {
                    if (NlqModel.simpleName(candidate.qualifiedName()).equals(name)) {
                        pc = candidate;
                        break;
                    }
                }
            }
            if (pc != null) {
                primaryClasses.put(pc.qualifiedName(), pc);
            }
        }

        Set<String> primaryNames = new HashSet<>();
        for (ClassDefinition pc : primaryClasses.values()) {
            primaryNames.add(pc.qualifiedName());
            primaryNames.add(NlqModel.simpleName(pc.qualifiedName()));
        }

        // Build per-class association target map
        Map<String, List<String>> assocTargets = new LinkedHashMap<>();
        for (ClassDefinition pc : primaryClasses.values()) {
            assocTargets.put(pc.qualifiedName(), new ArrayList<>());
        }
        for (AssociationDefinition assoc : allAssociations.values()) {
            String t1 = NlqModel.typeName(assoc.property1().targetClass());
            String t2 = NlqModel.typeName(assoc.property2().targetClass());
            // If t1 is primary, it navigates to t2
            for (ClassDefinition pc : primaryClasses.values()) {
                String qn = pc.qualifiedName();
                String sn = NlqModel.simpleName(pc.qualifiedName());
                if (t1.equals(qn) || t1.equals(sn)) {
                    assocTargets.get(qn).add(simpleName(t2));
                }
                if (t2.equals(qn) || t2.equals(sn)) {
                    assocTargets.get(qn).add(simpleName(t1));
                }
            }
        }

        // Build schema text
        StringBuilder sb = new StringBuilder();
        sb.append("=== Classes ===\n\n");
        for (ClassDefinition pc : primaryClasses.values()) {
            appendClassFull(sb, pc, allClasses);
            List<String> targets = assocTargets.get(pc.qualifiedName());
            if (targets != null && !targets.isEmpty()) {
                // Deduplicate and sort
                List<String> unique = targets.stream().distinct().sorted().toList();
                sb.append("  Associations: → ").append(String.join(", ", unique)).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String simpleName(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf("::");
        return idx >= 0 ? qualifiedName.substring(idx + 2) : qualifiedName;
    }

    // ==================== Formatting ====================

    private static void appendClassFull(StringBuilder sb, ClassDefinition pc,
            Map<String, ClassDefinition> allClasses) {
        sb.append("Class ").append(pc.qualifiedName());

        // Stereotypes
        if (!pc.stereotypes().isEmpty()) {
            sb.append(" <<");
            for (int i = 0; i < pc.stereotypes().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pc.stereotypes().get(i).profileName() + "." + pc.stereotypes().get(i).stereotypeName());
            }
            sb.append(">>");
        }
        sb.append("\n");

        // Class-level description
        String desc = NlqModel.tag(pc.taggedValues(), "nlq::NlqProfile", "description");
        if (desc == null) desc = NlqModel.tag(pc.taggedValues(), "doc", "doc");
        if (desc != null) {
            sb.append("  Description: ").append(desc).append("\n");
        }

        // Synonyms
        String syn = NlqModel.tag(pc.taggedValues(), "nlq::NlqProfile", "synonyms");
        if (syn != null) {
            sb.append("  Synonyms: ").append(syn).append("\n");
        }

        // Business domain
        String domain = NlqModel.tag(pc.taggedValues(), "nlq::NlqProfile", "businessDomain");
        if (domain != null) {
            sb.append("  Domain: ").append(domain).append("\n");
        }

        // When to use (routing hint)
        String whenToUse = NlqModel.tag(pc.taggedValues(), "nlq::NlqProfile", "whenToUse");
        if (whenToUse != null) {
            sb.append("  When to use: ").append(whenToUse).append("\n");
        }

        // Properties
        sb.append("  Properties:\n");
        for (var prop : NlqModel.allProperties(pc, allClasses)) {
            sb.append("    - ").append(prop.name())
              .append(": ").append(NlqModel.typeName(prop.type()))
              .append(NlqModel.multText(prop.multiplicity()));

            String propDesc = NlqModel.tag(prop.taggedValues(), "nlq::NlqProfile", "description");
            if (propDesc != null) {
                sb.append("  // ").append(propDesc);
            }

            String unit = NlqModel.tag(prop.taggedValues(), "nlq::NlqProfile", "unit");
            if (unit != null) {
                sb.append(" [").append(unit).append("]");
            }

            String sample = NlqModel.tag(prop.taggedValues(), "nlq::NlqProfile", "sampleValues");
            if (sample != null) {
                sb.append(" e.g. ").append(sample);
            }

            sb.append("\n");
        }
        sb.append("\n");
    }

    private static void appendClassSummary(StringBuilder sb, ClassDefinition pc,
            Map<String, ClassDefinition> allClasses) {
        sb.append("Class ").append(pc.qualifiedName());

        String desc = NlqModel.tag(pc.taggedValues(), "nlq::NlqProfile", "description");
        if (desc == null) desc = NlqModel.tag(pc.taggedValues(), "doc", "doc");
        if (desc != null) {
            sb.append(" — ").append(desc);
        }

        sb.append(" (").append(NlqModel.allProperties(pc, allClasses).size()).append(" properties)\n");
    }

    private static void appendAssociation(StringBuilder sb, AssociationDefinition assoc) {
        sb.append(NlqModel.typeName(assoc.property1().targetClass()))
          .append(".").append(assoc.property2().propertyName())
          .append(" → ")
          .append(NlqModel.typeName(assoc.property2().targetClass()))
          .append(NlqModel.multText(assoc.property2().multiplicity()));

        sb.append("  |  ");

        sb.append(NlqModel.typeName(assoc.property2().targetClass()))
          .append(".").append(assoc.property1().propertyName())
          .append(" → ")
          .append(NlqModel.typeName(assoc.property1().targetClass()))
          .append(NlqModel.multText(assoc.property1().multiplicity()));

        sb.append("\n");
    }

    /**
     * Extracts routing hints from model metadata for the semantic router.
     * Builds a compact string of disambiguation hints from whenToUse and exampleQuestions tags.
     * Returns empty string if no hints are found (model-agnostic).
     */
    public static String extractRoutingHints(Set<String> classNames, ParsedModel model) {
        Map<String, ClassDefinition> allClasses = NlqModel.allClasses(model);
        StringBuilder sb = new StringBuilder();

        for (String name : classNames) {
            ClassDefinition pc = allClasses.get(name);
            if (pc == null) {
                for (ClassDefinition candidate : allClasses.values()) {
                    if (NlqModel.simpleName(candidate.qualifiedName()).equals(name)) {
                        pc = candidate;
                        break;
                    }
                }
            }
            if (pc == null) continue;

            String whenToUse = NlqModel.tag(pc.taggedValues(), "nlq::NlqProfile", "whenToUse");
            String examples = NlqModel.tag(pc.taggedValues(), "nlq::NlqProfile", "exampleQuestions");

            if (whenToUse != null || examples != null) {
                sb.append(NlqModel.simpleName(pc.qualifiedName())).append(": ");
                if (whenToUse != null) {
                    sb.append(whenToUse);
                }
                if (examples != null) {
                    if (whenToUse != null) sb.append(" ");
                    sb.append("(examples: ").append(examples.replace("|", ", ")).append(")");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private static boolean matchesAny(String name, Set<String> names) {
        if (names.contains(name)) return true;
        // Try simple name extraction
        if (name.contains("::")) {
            String simple = name.substring(name.lastIndexOf("::") + 2);
            return names.contains(simple);
        }
        return false;
    }
}
