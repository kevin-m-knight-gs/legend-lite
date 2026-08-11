package com.legend.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable set of {@code import} statements collected from one Pure source.
 *
 * <p>Pure import syntax — BOTH references are wildcard-only (engine grammar
 * {@code IMPORT packagePath STAR}, legend-pure M3 identical):
 * <ul>
 *   <li>{@code import package::name::*;} &mdash; wildcard import</li>
 * </ul>
 * The old specific-import form ({@code import a::b::C;}) was a lite-only
 * invention; the parser refuses it (invention census batch 2) and the
 * simple-name&nbsp;&rarr;&nbsp;FQN tier this record once carried is gone.
 *
 * <p>Records {@link #wildcards()} &mdash; package paths whose contents are
 * in scope (e.g. {@code "simple::model"}).
 *
 * <p>This is <strong>pure data</strong>. Name resolution &mdash; turning a
 * simple name into a fully qualified one given an {@code ImportScope}
 * and the universe of known elements &mdash; lives in {@code NameResolver}
 * (Phase D). Putting it here would invite premature, ambiguity-prone
 * lookups before the model is fully populated.
 *
 * <p>Mirrors engine's {@code com.gs.legend.model.def.ImportScope}, but
 * immutable: built once by the parser via {@link Builder}, never mutated.
 */
public record ImportScope(List<String> wildcards) {

    public ImportScope {
        wildcards = wildcards == null ? List.of() : List.copyOf(wildcards);
    }

    /** Empty scope &mdash; no imports. */
    public static ImportScope empty() {
        return new ImportScope(List.of());
    }

    /** {@code true} if this scope holds no imports. */
    public boolean isEmpty() {
        return wildcards.isEmpty();
    }

    /**
     * Mutable builder used by {@link ElementParser} while walking imports.
     * Constructs an immutable {@link ImportScope} via {@link #build()}.
     */
    public static final class Builder {
        private final List<String> wildcards = new ArrayList<>();

        /** Add a full import path — a package wildcard, the only form the
         *  references admit; anything else is refused LOUDLY. */
        public Builder add(String importStatement) {
            String path = importStatement.trim();
            if (!path.endsWith("::*") || path.length() == 3) {
                // LOUD: a non-wildcard path here means a parser gate was
                // bypassed — real Pure imports packages, full stop
                throw new IllegalArgumentException(
                        "malformed import '" + path + "': an import must be"
                                + " a package wildcard (a::b::*)");
            }
            String pkg = path.substring(0, path.length() - 3);
            if (!wildcards.contains(pkg)) {
                wildcards.add(pkg);
            }
            return this;
        }

        public ImportScope build() {
            return new ImportScope(wildcards);
        }
    }
}
