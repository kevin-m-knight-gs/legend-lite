package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;
import java.util.Optional;

/**
 * A checked {@code serialize(#{Class{…}}#)} (engine {@code SerializeChecker})
 * &mdash; serializes a class collection through a validated graph-fetch tree;
 * {@code String[1]} from the registered signature.
 *
 * @param source the class collection being serialized
 * @param tree   the validated property tree
 * @param config the optional serialization config argument
 * @param info   {@code String[1]}
 */
public record TypedSerialize(TypedSpec source, List<TypedGraphTree> tree,
                             Optional<TypedSpec> config, ExprType info) implements TypedSpec {
    public TypedSerialize {
        tree = List.copyOf(tree);
    }

    @Override
    public List<TypedSpec> children() {
        return config
                .map(c -> List.of(source, c))
                .orElseGet(() -> List.of(source));
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, config.isPresent() ? 2 : 1, "TypedSerialize");
        return new TypedSerialize(kids.get(0), tree,
                config.isPresent() ? java.util.Optional.of(kids.get(1))
                        : java.util.Optional.empty(), info);
    }
}
