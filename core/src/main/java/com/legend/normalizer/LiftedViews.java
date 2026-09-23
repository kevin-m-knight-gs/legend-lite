// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.compiler.SynthFqn;
import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;
import com.legend.error.NotImplementedException;
import com.legend.model.DatabaseDefinition;
import com.legend.model.FunctionDefinition;
import com.legend.model.PackageableElement;
import com.legend.model.ParsedModel;
import com.legend.model.SynthHat;
import com.legend.protocol.Multiplicity;
import com.legend.protocol.TypeExpression;
import com.legend.protocol.spec.ValueSpecification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE store VIEWS as lifted functions (E.5) — ONE owner of every view's
 * relation body. A view is a zero-arg relation function
 * ({@code <db>$view$<name>(): Any[*]}, provenance {@link SynthHat#VIEW})
 * whose single body expression is {@link ViewRelation#viewRelationExpr}'s
 * output; every consumer — the mapping normalizer's 17 expansion sites
 * (a class on a view, a join hop onto a view, an association end on a
 * view) and the lift itself (a view whose root or hop is another view) —
 * reads the SAME body from here, keyed by the view definition's identity
 * (two schemas may declare a view of one name with different bodies).
 *
 * <p>Built BEFORE the mapping normalizer runs and handed to it as an input
 * of the same phase (T4.1 invariant 5: the normalizer never writes into the
 * model index). Bodies are memoized on demand with an identity cycle guard
 * (a view expanding through itself is loud); {@link #liftAll} makes the
 * lift eager, like E.2–E.4. A view whose translation walls keeps its wall:
 * under a wall sink it is a walled element, under the strict entry it
 * fails the build (USER RULING 2026-09-22: strict), and a mapping that
 * later reads it meets the same wall at its own site.
 */
final class LiftedViews {

    private record Owner(String dbFqn, String liftName) {
    }

    private final ModelBuilder model;
    private final Map<DatabaseDefinition.ViewDefinition, Owner> owners = new IdentityHashMap<>();
    private final List<DatabaseDefinition.ViewDefinition> order = new ArrayList<>();
    private final Map<DatabaseDefinition.ViewDefinition, FunctionDefinition> functions =
            new IdentityHashMap<>();
    private final Map<DatabaseDefinition.ViewDefinition, RuntimeException> walls =
            new IdentityHashMap<>();
    private final Set<DatabaseDefinition.ViewDefinition> expanding =
            Collections.newSetFromMap(new IdentityHashMap<>());

    LiftedViews(ParsedModel parsed, ModelBuilder model) {
        this.model = model;
        for (PackageableElement el : parsed.elements()) {
            if (!(el instanceof DatabaseDefinition db)) {
                continue;
            }
            for (DatabaseDefinition.ViewDefinition v : db.views()) {
                register(db, v, v.name());
            }
            for (DatabaseDefinition.SchemaDefinition s : db.schemas()) {
                for (DatabaseDefinition.ViewDefinition v : s.views()) {
                    register(db, v, s.name() + "." + v.name());
                }
            }
        }
    }

    private void register(DatabaseDefinition db, DatabaseDefinition.ViewDefinition v,
            String liftName) {
        owners.put(v, new Owner(db.qualifiedName(), liftName));
        order.add(v);
    }

    /** The view's relation body — the lifted function's one body expression,
     *  computed once. Throws the view's own wall when it cannot be expanded
     *  (the same exception at every site, the lift's or a mapping's). */
    ValueSpecification body(DatabaseDefinition.ViewDefinition view) {
        FunctionDefinition fn = functions.get(view);
        if (fn != null) {
            return fn.body().get(0);
        }
        RuntimeException wall = walls.get(view);
        if (wall != null) {
            throw wall;
        }
        Owner owner = owners.get(view);
        if (owner == null) {
            throw new ModelException(LegendCompileException.Phase.NORMALIZE,
                    "view '" + view.name() + "' is not declared by any database of this model");
        }
        if (!expanding.add(view)) {
            throw new ModelException(LegendCompileException.Phase.NORMALIZE,
                    "view '" + owner.liftName() + "' expands through itself (cyclic"
                    + " view-on-view chain); store=" + owner.dbFqn());
        }
        try {
            ValueSpecification body = ViewRelation.viewRelationExpr(
                    view, owner.liftName(), owner.dbFqn(), model, null, this);
            functions.put(view, new FunctionDefinition(
                    SynthFqn.view(owner.dbFqn(), owner.liftName()), List.of(), List.of(), List.of(),
                    new TypeExpression.NameRef(com.legend.compiler.element.type.PlatformTypes.ANY),
                    Multiplicity.Concrete.ZERO_MANY, List.of(body), List.of(), List.of())
                    .withSynthesizedFrom(new FunctionDefinition.Synthesized(
                            SynthHat.VIEW, owner.dbFqn(), owner.liftName())));
            return body;
        } catch (ModelException | NotImplementedException e) {
            walls.put(view, e);
            throw e;
        } finally {
            expanding.remove(view);
        }
    }

    /** E.5, eager like E.2–E.4: every view lifted; a wall recorded under the
     *  lifted FQN when a sink exists, thrown under the strict entry. */
    void liftAll(java.util.@com.legend.Nullable Map<String, String> wallSink) {
        for (DatabaseDefinition.ViewDefinition v : order) {
            try {
                body(v);
            } catch (ModelException | NotImplementedException e) {
                if (wallSink == null) {
                    throw e;
                }
                Owner owner = java.util.Objects.requireNonNull(owners.get(v), "registered view");
                wallSink.putIfAbsent(SynthFqn.view(owner.dbFqn(), owner.liftName()),
                        String.valueOf(e.getMessage()));
            }
        }
    }

    /** The lifted functions, in declaration order (walled views absent). */
    List<FunctionDefinition> functions() {
        List<FunctionDefinition> out = new ArrayList<>(functions.size());
        for (DatabaseDefinition.ViewDefinition v : order) {
            FunctionDefinition fn = functions.get(v);
            if (fn != null) {
                out.add(fn);
            }
        }
        return out;
    }
}
