// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RUNTIME-dispatched {@code match} — kept when static dispatch cannot
 * decide soundly: at least one branch's declared type is a STRICT
 * SUBTYPE of the input's static type, so the first-accepting rule would
 * silently take a wider arm where real pure (Match.java walks the
 * runtime value) takes the narrow one. Every arm keeps its own typed
 * body with the parameter bound at the arm's DECLARED type.
 *
 * <p>Consumed ONLY by the host evaluation channel; SQL lowering has no
 * call frame for runtime type dispatch and walls LOUDLY.
 */
public record TypedMatchRuntime(
        TypedSpec input,
        List<Arm> arms,
        Optional<String> extraParam,
        Optional<TypedSpec> extra,
        Optional<TypedSpec> dynamicArms,
        ExprType info) implements TypedSpec {

    /** One branch: the declared type FQN it accepts at runtime, the
     * parameter it binds, and its typed body. */
    public record Arm(String typeFqn, String param, TypedSpec body) {
    }

    public TypedMatchRuntime {
        arms = List.copyOf(arms);
        if (arms.isEmpty()) {
            throw new IllegalArgumentException("TypedMatchRuntime needs arms");
        }
    }

    /** The common form: every arm spelled. */
    public TypedMatchRuntime(TypedSpec input, List<Arm> arms, Optional<String> extraParam,
            Optional<TypedSpec> extra, ExprType info) {
        this(input, arms, extraParam, extra, Optional.empty(), info);
    }

    /**
     * WORLD_MAP §4 — {@code dynamicArms}: a PREFIX of the arm collection
     * that is an EXPRESSION, not spelled lambdas (the engine's
     * {@code $state.extensions->map(e|…)->concatenate([arms])} idiom —
     * extension-contributed arms). The unroll must fold it to the EMPTY
     * collection before the spelled arms dispatch (extensions = []); a
     * non-empty residual is a loud wall (the lowering has no runtime arm
     * list). Dispatch order: the dynamic arms come FIRST in the engine,
     * so a non-empty prefix can never be skipped.
     */

    @Override
    public List<TypedSpec> children() {
        List<TypedSpec> out = new ArrayList<>();
        out.add(input);
        extra.ifPresent(out::add);
        dynamicArms.ifPresent(out::add);
        for (Arm a : arms) {
            out.add(a.body());
        }
        return List.copyOf(out);
    }

    @Override
    public TypedSpec withChildren(List<TypedSpec> kids) {
        int fixed = 1 + (extra.isPresent() ? 1 : 0) + (dynamicArms.isPresent() ? 1 : 0);
        TypedSpec.expectChildren(kids, fixed + arms.size(),
                "TypedMatchRuntime");
        List<Arm> newArms = new ArrayList<>(arms.size());
        for (int i = 0; i < arms.size(); i++) {
            Arm a = arms.get(i);
            newArms.add(new Arm(a.typeFqn(), a.param(), kids.get(fixed + i)));
        }
        int dynAt = 1 + (extra.isPresent() ? 1 : 0);
        return new TypedMatchRuntime(kids.get(0), newArms, extraParam,
                extra.map(ignored -> kids.get(1)),
                dynamicArms.map(ignored -> kids.get(dynAt)), info);
    }
}
