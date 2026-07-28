package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * A checked {@code graphFetch(#{Class{…}}#)} (engine {@code TypedGraphFetch})
 * &mdash; an object-graph projection: every tree property validates against its
 * owner class (recursively), and the result is the SOURCE type unchanged
 * (engine's rule &mdash; formatting is an execution concern).
 *
 * @param source the class collection being fetched
 * @param tree   the validated property tree
 * @param info   the source type unchanged
 */
public record TypedGraphFetch(TypedSpec source, List<TypedGraphTree> tree, ExprType info,
                              boolean checked) implements TypedSpec {

    /** The plain (unchecked) projection. */
    public TypedGraphFetch(TypedSpec source, List<TypedGraphTree> tree, ExprType info) {
        this(source, tree, info, false);
    }

    public TypedGraphFetch {
        tree = List.copyOf(tree);
    }

    // tree ARGS are deliberately NOT children — they stay VERBATIM through
    // rewrites: the serialize key renders their SOURCE spelling (engine:
    // product($bd), not the bound date); date resolution reads the let env
    // at the resolver instead (queryLets — engine
    // resolveMilestoningDateParams/inScopeVars). Same rule in TypedSerialize.
    @Override
    public List<TypedSpec> children() {
        return List.of(source);
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 1, "TypedGraphFetch");
        return new TypedGraphFetch(kids.get(0), tree, info, checked);
    }
}
