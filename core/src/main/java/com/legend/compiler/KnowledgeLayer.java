// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler;

import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;
import com.legend.model.AssociationDefinition;
import com.legend.model.ClassDefinition;
import com.legend.model.PackageableElement;
import com.legend.model.ParsedModel;
import com.legend.protocol.DerivedPropertyDefinition;
import com.legend.protocol.TypeExpression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * F1 &mdash; the KNOWLEDGE layer's passes over a name-resolved model
 * (docs/T4_1_KNOWLEDGE_BEFORE_NORMALIZATION_2026_09_13.md &sect;7): what
 * the compiled model knows about classes, properties and associations is
 * settled here, BEFORE Phase E normalizes mappings against it. Step 1 of
 * that program: association qualified-property adoption, moved out of the
 * normalizer (it created the package's one {@code ClassDefinition} and
 * raised two model errors from inside E).
 */
public final class KnowledgeLayer {

    private KnowledgeLayer() {}

    /**
     * Association QUALIFIED properties adopt into the class that owns
     * them (the end OPPOSITE the one the property returns &mdash; real
     * pure: an association qualified property is an alternate accessor
     * of one end, callable on the other end's class). After adoption the
     * single class-derived funnel (E.2, findProperty, $prop$ lifting)
     * covers them with no second path. The association keeps its
     * declaration (the faithful source image, like a class keeps its
     * derived bodies); the owner class gains the property.
     *
     * <p>Runs post-NameResolver, so end targets and return types are FQNs.
     * A property naming no unique owning end is a MODEL error: strict
     * builds throw; a tolerant (module) build records the association in
     * {@code wallSink} and adopts nothing from it.
     */
    public static ParsedModel adoptAssociationQualifiedProperties(ParsedModel parsed,
            java.util.@com.legend.Nullable Map<String, String> wallSink) {
        Objects.requireNonNull(parsed, "parsed");
        Map<String, List<DerivedPropertyDefinition>> adoptions =
                new LinkedHashMap<>();
        for (PackageableElement el : parsed.elements()) {
            if (!(el instanceof AssociationDefinition ad)
                    || ad.derivedProperties().isEmpty()) {
                continue;
            }
            List<Map.Entry<String, DerivedPropertyDefinition>> own = new ArrayList<>();
            try {
                for (DerivedPropertyDefinition dp : ad.derivedProperties()) {
                    own.add(Map.entry(ownerOrThrow(ad, dp), dp));
                }
            } catch (ModelException e) {
                if (wallSink == null) {
                    throw e;
                }
                wallSink.putIfAbsent(ad.qualifiedName(),
                        String.valueOf(e.getMessage()).split("\n")[0]);
                continue;
            }
            for (Map.Entry<String, DerivedPropertyDefinition> e : own) {
                adoptions.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
        }
        if (adoptions.isEmpty()) {
            return parsed;
        }
        List<PackageableElement> out = new ArrayList<>(parsed.elements().size());
        for (PackageableElement el : parsed.elements()) {
            if (el instanceof ClassDefinition cd
                    && adoptions.containsKey(cd.qualifiedName())) {
                List<DerivedPropertyDefinition> merged =
                        new ArrayList<>(cd.derivedProperties());
                merged.addAll(adoptions.get(cd.qualifiedName()));
                out.add(new ClassDefinition(cd.qualifiedName(), cd.typeParams(), cd.typeVariables(),
                        cd.superClasses(), cd.properties(), merged, cd.constraints(),
                        cd.stereotypes(), cd.taggedValues(), cd.isNative()));
            } else {
                out.add(el);
            }
        }
        // full-arg pass-through: source/offsets/per-element imports,
        // per-element sources and unclaimed sections all survive
        return new ParsedModel(out, parsed.imports(), parsed.source(),
                parsed.elementOffsets(), parsed.elementImports(),
                parsed.elementSources(), parsed.unclaimedSections());
    }

    /**
     * The class that OWNS an association's qualified property: the end
     * opposite the one the property returns; a self-association (both
     * ends the same class) owns its qualified properties itself
     * (AssociationProcessor: leftRawType == returnType ? right : left).
     * Empty when the return type identifies no unique owning end. EXACT
     * FQN comparison &mdash; the model is name-resolved.
     */
    public static Optional<String> qualifiedPropertyOwner(AssociationDefinition ad,
            DerivedPropertyDefinition dp) {
        String t1 = rawName(ad.property1().targetClass());
        String t2 = rawName(ad.property2().targetClass());
        String ret = rawName(dp.type());
        if (t1.equals(t2)) {
            return ret.equals(t1) ? Optional.of(t1) : Optional.empty();
        }
        if (ret.equals(t1)) {
            return Optional.of(t2);
        }
        return ret.equals(t2) ? Optional.of(t1) : Optional.empty();
    }

    private static String ownerOrThrow(AssociationDefinition ad, DerivedPropertyDefinition dp) {
        Optional<String> owner = qualifiedPropertyOwner(ad, dp);
        if (owner.isPresent()) {
            return owner.get();
        }
        String t1 = rawName(ad.property1().targetClass());
        String t2 = rawName(ad.property2().targetClass());
        String ret = rawName(dp.type());
        String why = t1.equals(t2)
                ? "', which is neither end of the self-association"
                : "', which does not identify a unique owning end";
        throw new ModelException(LegendCompileException.Phase.MODEL,
                "association '" + ad.qualifiedName() + "' qualified property '"
                        + dp.name() + "' returns '" + ret + why,
                ad.qualifiedName());
    }

    private static String rawName(TypeExpression t) {
        return switch (t) {
            case TypeExpression.NameRef n -> n.name();
            case TypeExpression.Generic g -> g.name();
            default -> t.toString();
        };
    }
}
