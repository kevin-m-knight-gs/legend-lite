package com.legend.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of Phase E ({@link ModelNormalizer#normalize}) &mdash; the canonical
 * post-normalization model ({@code docs/CLEAN_SHEET_INVERSION.md} &sect;4).
 *
 * <p>{@code elements} holds the structural elements (parser records, untouched
 * by Phase E) <em>plus</em> every behavior function Phase E lifted from a body
 * site (mapping transform, derived property, constraint, service query) as an
 * ordinary top-level {@link FunctionDefinition} &mdash; distinguishable from a
 * user-written function only by its reserved {@code $}-sigil FQN and its
 * {@link FunctionDefinition#synthesizedFrom()} provenance tag. Phase F ingests
 * lifted functions through the same {@code case FunctionDefinition} arm as
 * user functions; there is no separate flatten step.
 *
 * <p>What Phase E LEARNED about a mapping (poisons, mixed unions, union key
 * threads, the nullable census) rides the compiled {@link MappingDefinition}
 * itself ({@link MappingDefinition#facts()}, T4.1 step 2) &mdash; this record
 * carries no side channels for it.
 *
 * <p>The type is the phase gate: {@code normalize} accepts a
 * {@link com.legend.model.ParsedModel} and returns a {@code NormalizedModel},
 * so a model cannot be re-normalized (the duplicate-synth footgun dies at the
 * signature) and Phase F entry points can demand normalization at the type
 * level.
 *
 * @param elements       structural elements + all functions (user-written and lifted)
 * @param imports        import scope carried through from the parsed model
 * @param legacySurfaces READ-ONLY archive of the pre-Door-1 mapping DSL (the
 *                       authored mapping plus its JSON identity sets) for
 *                       ANALYSIS consumers (static lineage #44) &mdash; the
 *                       compilation pipeline never reads it
 */
public record NormalizedModel(List<PackageableElement> elements, ImportScope imports,
        java.util.Map<String, LegacyMappingDefinition> legacySurfaces) {

    /** Without legacy surfaces (tests, mapping-free paths). */
    public NormalizedModel(List<PackageableElement> elements, ImportScope imports) {
        this(elements, imports, java.util.Map.of());
    }

    public NormalizedModel {
        elements = elements == null ? List.of() : List.copyOf(elements);
        if (imports == null) {
            imports = ImportScope.empty();
        }
        legacySurfaces = legacySurfaces == null
                ? java.util.Map.of() : java.util.Map.copyOf(legacySurfaces);
    }

    /**
     * Derived index: owner FQN &rarr; FQNs of the functions Phase E lifted
     * from that owner's body sites, in element order.
     *
     * <p>Computed by grouping {@code elements}' {@code synthesizedFrom}
     * provenance &mdash; never a constructor input, so it cannot be populated
     * out of sync with the element list (the index/sidecar rule,
     * {@code docs/CLEAN_SHEET_INVERSION.md} &sect;4). Nothing in phases F+
     * reads this; it exists for the incremental-invalidation layer
     * ("which functions came from this owner"). Recomputed per call today;
     * memoize behind this accessor if/when an incremental layer needs it hot.
     */
    public Map<String, List<String>> liftedByOwner() {
        Map<String, List<String>> byOwner = new LinkedHashMap<>();
        for (PackageableElement el : elements) {
            if (el instanceof FunctionDefinition fd && fd.isSynthesized()) {
                byOwner.computeIfAbsent(java.util.Objects.requireNonNull(
                        fd.synthesizedFrom()).ownerFqn(),
                        k -> new ArrayList<>())
                        .add(fd.qualifiedName());
            }
        }
        byOwner.replaceAll((owner, fqns) -> List.copyOf(fqns));
        return Collections.unmodifiableMap(byOwner);
    }
}
