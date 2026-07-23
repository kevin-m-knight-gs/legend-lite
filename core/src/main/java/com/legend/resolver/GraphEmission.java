// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedFuncCol;
import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedGraphTree;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNavigate;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedNewInstanceCast;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSerializeGraph;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.MappingResolutionException;
import com.legend.error.NotImplementedException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.UnaryOperator;
/**
 * GRAPH-terminal emission (plan H4a SNAPSHOT): the serialize envelope —
 * leaves substituted over the row, class-typed children as correlated
 * per-hop nodes (the EXISTS material shape), to-many children
 * array-wrapped; the implicit envelope enumerates the class's scalar
 * bindings plus the GENERATED temporal-context dates.
 */
final class GraphEmission {

    private final ModelContext ctx;
    private final ClassSources sources;
    private final AssociationJoins assocMaterial;
    private final TemporalFrame temporal;
    /** Mapping dispatch (context, classFqn) -> mappingFqn — the
     * resolver's runtime-aware routing. */
    private final BiFunction<StoreResolver.Context,
            String, String> dispatch;
    /** Fresh child row-var mint (shares the resolver's counter). */
    private final IntSupplier freshVar;

    GraphEmission(ModelContext ctx, ClassSources sources,
            AssociationJoins assocMaterial, TemporalFrame temporal,
            BiFunction<StoreResolver.Context, String,
                    String> dispatch,
            IntSupplier freshVar) {
        this.ctx = ctx;
        this.sources = sources;
        this.assocMaterial = assocMaterial;
        this.temporal = temporal;
        this.dispatch = dispatch;
        this.freshVar = freshVar;
    }

    /**
     * The implicit-serialize tree for a BARE class root: one leaf per
     * SCALAR binding, declaration order — class-typed bindings (embedded
     * ctors, navigation slots) are graph CHILDREN territory and stay out
     * of the bare-root envelope (plan §E10).
     */
    List<TypedGraphTree> synthesizeScalarTree(ClassSource cs) {
        List<TypedGraphTree> tree = new ArrayList<>();
        for (Map.Entry<String, TypedSpec> e : cs.bindings().entrySet()) {
            // subtype-dispatch pseudo-bindings are CAST machinery, not
            // properties of the class — the implicit envelope never
            // serializes them (they broke testAllForB when the #71
            // same-source synthesis joined the binding table)
            if (com.legend.model.ClassMapping.isSubTypeColumn(e.getKey())) {
                continue;
            }
            TypedSpec inner = e.getValue();
            if (inner instanceof TypedNativeCall c
                    && c.args().size() == 1
                    && c.callee().qualifiedName().equals("meta::pure::functions::multiplicity::toOne")) {
                inner = c.args().get(0);
            }
            if (inner instanceof TypedNewInstance
                    || inner.info().type()
                            instanceof Type.ClassType) {
                continue;
            }
            tree.add(new TypedGraphTree(e.getKey(), List.of()));
        }
        // GENERATED temporal-context properties ride the implicit envelope
        // (engine: temporal instances serialize their dates; sweeps read
        // each version row's own validity-start)
        String strat = temporal.temporalStrategy(cs.classFqn());
        if (strat != null) {
            if (!"processingtemporal".equals(strat)
                    && !cs.bindings().containsKey("businessDate")) {
                tree.add(new TypedGraphTree("businessDate", List.of()));
            }
            if (!"businesstemporal".equals(strat)
                    && !cs.bindings().containsKey("processingDate")) {
                tree.add(new TypedGraphTree("processingDate", List.of()));
            }
        }
        return tree;
    }

    /** The generated date's VALUE for an envelope leaf: the fetch context
     * date when one exists (point fetch), else the row's own
     * validity-start milestone column (version sweep); {@code null} when
     * the property is not a generated date here. */
    TypedSpec generatedDateLeaf(ClassSource cs, String prop,
            Type.RelationType rowType,
            String rowVar) {
        if ((!prop.equals("businessDate") && !prop.equals("processingDate"))
                || temporal.temporalStrategy(cs.classFqn()) == null) {
            return null;
        }
        // the point-fetch CONSTANT needs no milestone columns — a temporal
        // class on a capability-tolerance (non-milestoned) table still has
        // a well-defined context date (audit 14 B-F8: the column check
        // walled it needlessly)
        TypedSpec ctxDate = prop.equals("businessDate")
                ? temporal.root().business() : temporal.root().processing();
        if (ctxDate != null) {
            return ctxDate;
        }
        Map<String, String> mc = temporal.milestoneColumnsOf(cs.pipeline(), cs.classFqn());
        if (mc.isEmpty()) {
            return null;
        }
        String col = mc.get(prop.equals("processingDate")
                ? TemporalFrame.GEN_PROCESSING_DATE
                : TemporalFrame.GEN_BUSINESS_DATE);
        if (col == null) {
            return null;
        }
        var colDef = rowType.columns().stream()
                .filter(c -> c.name().equals(col)).findFirst().orElse(null);
        if (colDef == null) {
            return null;
        }
        return new TypedPropertyAccess(
                new TypedVariable(rowVar,
                        new ExprType(rowType,
                                com.legend.compiler.element.type.Multiplicity
                                        .Bounded.ONE)),
                col, new ExprType(
                        colDef.type(), colDef.multiplicity()));
    }

    /**
     * One envelope node (recursive): {@code leaves} substitute the class's
     * bindings over the row var; class-typed children become correlated
     * per-hop nodes — the child pipeline FILTERED by the association
     * condition with the PARENT row var free (the EXISTS material shape),
     * to-many children array-wrapped. Same-leaf pruning is by construction:
     * only tree entries are emitted.
     */
    TypedSerializeGraph buildGraphNode(ClassSource cs, TypedSpec pipeline,
            Map<String, String> slotPrefixes, Set<String> stripped, String rowVar,
            List<TypedGraphTree> tree, StoreResolver.Context context, boolean arrayWrap,
            ExprType info) {
        var rowType = (Type.RelationType)
                pipeline.info().type();
        UnaryOperator<TypedSpec> toRow = v -> new TypedVariable(
                rowVar, new ExprType(rowType,
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
        List<TypedFuncCol> leaves = new ArrayList<>();
        List<TypedSerializeGraph.Child> children = new ArrayList<>();
        List<TypedSerializeGraph.SubTypePatch> subTypePatches = new ArrayList<>();
        for (TypedGraphTree node : tree) {
            // ->subType(@X){...}: the subtype VIEW — extracted builder
            if (node.subTypeFqn() != null) {
                subTypePatches.add(subTypePatch(cs, node, context, rowVar,
                        rowType, toRow));
                continue;
            }
            if (!node.children().isEmpty()
                    || (!cs.bindings().containsKey(node.property())
                            && ctx.findAssociationOf(cs.classFqn(), node.property())
                                    .isPresent())) {
                children.add(graphChild(cs, node, context, rowVar, rowType));
                continue;
            }
            TypedSpec binding = cs.bindings().get(node.property());
            if (binding == null) {
                // DERIVED (qualified) leaf: the class's parameterless
                // derived property inlines through its lifted body
                // function ($this reads substitute to the row bindings) —
                // the QUERY position gets this at the Typer front door;
                // tree leaves are bare names and inline here (task #78)
                TypedSpec derived = derivedLeaf(cs, node, context,
                        rowVar, rowType);
                if (derived != null) {
                    var dFn = new Type.FunctionType(
                            List.of(new Type.Param(rowType,
                                    com.legend.compiler.element.type
                                            .Multiplicity.Bounded.ONE)),
                            new Type.Param(derived.info().type(),
                                    derived.info().multiplicity()));
                    // the engine serializes qualifier leaves under the
                    // ALIAS when given, else the CALL spelling {"name()"}
                    leaves.add(new TypedFuncCol(node.alias() != null
                            ? node.alias() : callKey(node),
                            new TypedLambda(List.of(rowVar),
                                    List.of(derived),
                                    new ExprType(dFn,
                                            com.legend.compiler.element.type
                                                    .Multiplicity.Bounded
                                                    .ONE))));
                    continue;
                }
                // GENERATED temporal-context property on the envelope:
                // point fetch = the context date constant; VERSION SWEEP =
                // each row's own validity-start column (engine: the
                // property maps to BUS_FROM / PROCESSING_IN / snapshot)
                TypedSpec gen = generatedDateLeaf(cs, node.property(),
                        rowType, rowVar);
                if (gen == null) {
                    throw new MappingResolutionException("property '"
                            + node.property() + "' of class '" + cs.classFqn()
                            + "' is not mapped in mapping '" + cs.mappingFqn()
                            + "'", cs.classFqn());
                }
                var genFn = new Type.FunctionType(
                        List.of(new Type.Param(
                                rowType,
                                com.legend.compiler.element.type.Multiplicity
                                        .Bounded.ONE)),
                        new Type.Param(
                                gen.info().type(), gen.info().multiplicity()));
                leaves.add(new TypedFuncCol(keyOf(node),
                        new TypedLambda(List.of(rowVar), List.of(gen),
                                new ExprType(genFn,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE))));
                continue;
            }
            TypedSpec inner = binding;
            if (inner instanceof TypedNativeCall c
                    && c.args().size() == 1
                    && c.callee().qualifiedName().equals("meta::pure::functions::multiplicity::toOne")) {
                inner = c.args().get(0);
            }
            // A TO-MANY PRIMITIVE leaf ($row.<slot>.COL through a to-many
            // navigate slot): reading it as a joined scalar EXPLODES the
            // parent rows — the engine serializes ["abc","def"] per parent.
            // Emit a bare-value child: the slot target correlated on the
            // parent row, aggregating the raw column values.
            if (inner instanceof TypedPropertyAccess colPa
                    && colPa.source() instanceof TypedPropertyAccess slotPa
                    && slotPa.source() instanceof TypedVariable slotV
                    && slotV.name().equals(cs.rowVar())
                    // multiplicity of the RAW read (a toOne conformance
                    // wrapper on the binding would mask the [*])
                    && !(colPa.info().multiplicity()
                            instanceof com.legend.compiler.element.type
                                    .Multiplicity.Bounded b1
                            && Integer.valueOf(1).equals(b1.upper()))) {
                var navPrim = Pipelines.navSteps(cs.pipeline()).get(slotPa.property());
                if (navPrim != null) {
                    children.add(primitiveArrayChild(keyOf(node),
                            navPrim.target(), navPrim.predicate(),
                            colPa, rowVar, rowType));
                    continue;
                }
                var slotPrim = Pipelines.joinSlots(cs.pipeline()).get(slotPa.property());
                if (slotPrim != null) {
                    children.add(primitiveArrayChild(keyOf(node),
                            slotPrim.target(), slotPrim.condition(),
                            colPa, rowVar, rowType));
                    continue;
                }
            }
            // audit 23: any OTHER to-many read through a slot (wrapped in
            // a computed expression, deeper chain) would fall to the
            // joined-scalar path and EXPLODE parent rows — loud
            if (!(inner.info().multiplicity()
                    instanceof com.legend.compiler.element.type
                            .Multiplicity.Bounded bW
                    && Integer.valueOf(1).equals(bW.upper()))) {
                java.util.Set<String> slotish = new java.util.LinkedHashSet<>(
                        Pipelines.navSteps(cs.pipeline()).keySet());
                slotish.addAll(Pipelines.joinSlots(cs.pipeline()).keySet());
                if (Pipelines.referencesAliasOn(inner, cs.rowVar(), slotish)) {
                    throw new NotImplementedException("graph leaf '"
                            + node.property() + "' reads a TO-MANY slot"
                            + " through a computed expression — the joined-"
                            + "scalar emission would explode parent rows");
                }
            }
            if (inner instanceof TypedNewInstance) {
                throw new NotImplementedException("graph leaf '" + node.property()
                        + "' is an EMBEDDED class property — embedded graph"
                        + " children are not supported yet (H4b)");
            }
            if (inner instanceof TypedNewInstanceCast) {
                throw new NotImplementedException("graph property '" + node.property()
                        + "' is a MODEL-TO-MODEL cast binding — M2M graph"
                        + " children are not supported yet (H5c)");
            }
            // A leaf mapped through STRIPPED join slots (a nested child's
            // own joins materialize with empty demand) is a feature gap,
            // not a resolver bug (audit F5).
            if (Pipelines.referencesAliasOn(binding, cs.rowVar(), stripped)) {
                throw new NotImplementedException("graph leaf '" + node.property()
                        + "' of class '" + cs.classFqn() + "' is mapped through"
                        + " the class's own join slots — nested join demand"
                        + " inside a graph child is not supported yet (H4b)");
            }
            TypedSpec body = Pipelines.rewriteRowReads(binding, cs.rowVar(),
                    slotPrefixes, stripped, toRow);
            var fnType = new Type.FunctionType(
                    List.of(new Type.Param(rowType,
                            com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                    new Type.Param(
                            body.info().type(), body.info().multiplicity()));
            leaves.add(new TypedFuncCol(keyOf(node),
                    new TypedLambda(List.of(rowVar), List.of(body),
                            new ExprType(fnType,
                                    com.legend.compiler.element.type.Multiplicity.Bounded.ONE))));
        }
        // UNION-root row order: the engine serializes members in BRANCH
        // DECLARATION order (serial union); DuckDB's aggregation is scan-
        // ordered. The member WITNESS columns (row-type column order =
        // declaration order) become ORDER keys — TRUE-first per member.
        List<TypedFuncCol> orderKeys = new ArrayList<>();
        if (arrayWrap) {
            for (Type.Column c : rowType.columns()) {
                if (com.legend.model.ClassMapping
                        .witnessPrefixOf(c.name()) == null) {
                    continue;
                }
                TypedSpec wread = new TypedPropertyAccess(toRow.apply(null),
                        c.name(), new ExprType(c.type(), c.multiplicity()));
                var wFn = new Type.FunctionType(
                        List.of(new Type.Param(rowType,
                                com.legend.compiler.element.type
                                        .Multiplicity.Bounded.ONE)),
                        new Type.Param(c.type(), c.multiplicity()));
                orderKeys.add(new TypedFuncCol(c.name(),
                        new TypedLambda(List.of(rowVar), List.of(wread),
                                new ExprType(wFn,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE))));
            }
        }
        return new TypedSerializeGraph(pipeline, rowVar, leaves, children,
                arrayWrap, false, cs.classFqn(), info, false, subTypePatches,
                orderKeys);
    }

    /**
     * A TO-MANY PRIMITIVE leaf as a BARE-VALUE child: the navigate slot's
     * target relation, correlated on the parent row exactly like
     * {@link #correlatedGraphChild}, aggregating the single column's raw
     * values (TypedSerializeGraph.bareValue).
     */
    private TypedSerializeGraph.Child primitiveArrayChild(String property,
            TypedSpec targetPipeline, TypedLambda cond,
            TypedPropertyAccess colRead,
            String parentRowVar, Type.RelationType parentRowType) {
        Type.RelationType targetRow =
                (Type.RelationType) targetPipeline.info().type();
        String pVar = cond.parameters().get(0);
        String tVar = cond.parameters().get(1);
        List<TypedSpec> corrBody = cond.body().stream().map(x ->
                Pipelines.rewriteRowReads(x, pVar, Map.of(), Set.of(),
                        v -> new TypedVariable(parentRowVar,
                                new ExprType(parentRowType,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE))))
                .toList();
        TypedLambda corr = new TypedLambda(List.of(tVar), corrBody,
                new ExprType(
                        new Type.FunctionType(
                                List.of(new Type.Param(targetRow,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE)),
                                new Type.Param(Type.Primitive.BOOLEAN,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE)),
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
        TypedSpec childRel = new TypedFilter(targetPipeline, corr,
                targetPipeline.info());
        // same freshness guard as correlatedGraphChild (audit 23 #75 —
        // this mint lacked it): bump past any name the child value or
        // the correlation could capture
        Set<String> pcParams = new LinkedHashSet<>();
        StoreResolver.collectLambdaParams(colRead, pcParams);
        pcParams.addAll(corr.parameters());
        String childVar;
        do {
            childVar = "_r" + freshVar.getAsInt();
        } while (pcParams.contains(childVar));
        TypedSpec value = new TypedPropertyAccess(
                new TypedVariable(childVar,
                        new ExprType(targetRow,
                                com.legend.compiler.element.type
                                        .Multiplicity.Bounded.ONE)),
                colRead.property(), colRead.info());
        var fnType = new Type.FunctionType(
                List.of(new Type.Param(targetRow,
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                new Type.Param(value.info().type(), value.info().multiplicity()));
        TypedFuncCol leaf = new TypedFuncCol(property,
                new TypedLambda(List.of(childVar), List.of(value),
                        new ExprType(fnType,
                                com.legend.compiler.element.type
                                        .Multiplicity.Bounded.ONE)));
        TypedSerializeGraph node = new TypedSerializeGraph(childRel, childVar,
                List.of(leaf), List.of(), /*arrayWrap*/ true, /*bareValue*/ true,
                colRead.info());
        return new TypedSerializeGraph.Child(property, node);
    }

    /** One nested hop: correlated child pipeline + the child's own envelope. */
    TypedSerializeGraph.Child graphChild(ClassSource cs, TypedGraphTree node,
            StoreResolver.Context context, String parentRowVar,
            Type.RelationType parentRowType) {
        if (node.children().isEmpty()) {
            throw new NotImplementedException("graph child '" + node.property()
                    + "' of class '" + cs.classFqn() + "' has no sub-tree — a"
                    + " class-typed leaf serializes nothing; list its properties");
        }
        // A BINDING-backed head: the M2M SOURCE-NAV MARKER ($src.assocProp,
        // re-pointed at the composed row var by ClassSources) fans out as a
        // correlated child through the UPSTREAM association; every other
        // binding kind (embedded ctor, navigate slot, otherwise) stays loud.
        if (cs.bindings().containsKey(node.property())) {
            TypedSpec b0 = cs.bindings().get(node.property());
            TypedSpec inner = b0;
            // Unwrap the M2M cast (^Target($src.assocProp)) and toOne.
            if (inner instanceof TypedNewInstanceCast nic) {
                inner = nic.source();
            }
            if (inner instanceof TypedNativeCall c1
                    && c1.args().size() == 1
                    && c1.callee().qualifiedName().equals("meta::pure::functions::multiplicity::toOne")) {
                inner = c1.args().get(0);
            }
            if (inner instanceof TypedNewInstanceCast nic2) {
                inner = nic2.source();
            }
            if (inner instanceof TypedPropertyAccess pa
                    && pa.source() instanceof TypedVariable v
                    && v.name().equals(cs.rowVar())
                    && v.info().type() instanceof Type.ClassType srcCls
                    && ctx.findAssociationOf(srcCls.fqn(), pa.property()).isPresent()) {
                return m2mAssocChild(cs, node, srcCls.fqn(), pa.property(),
                        context, parentRowVar, parentRowType);
            }
            // A NAVIGATE-SLOT read ($row.<alias>, the relational
            // association injected into the source pipeline): the slot's
            // TypedNavigate carries the raw target and the join predicate.
            if (inner instanceof TypedPropertyAccess pa2
                    && pa2.source() instanceof TypedVariable v2
                    && v2.name().equals(cs.rowVar())) {
                var navSteps = Pipelines.navSteps(cs.pipeline());
                var nav = navSteps.get(pa2.property());
                if (nav != null) {
                    return navSlotChild(cs, node, nav,
                            b0 instanceof TypedNewInstanceCast nic0
                                    ? nic0.classFqn() : null,
                            context, parentRowVar, parentRowType);
                }
            }
            // EMBEDDED child: the ^Inner ctor's bindings read the PARENT
            // row — an INLINE json object, no join, no subquery (V1 §D.4
            // semantics at the graph envelope; task #78 H4b slice 1).
            // otherwise() takes the embedded partial: per-leaf FK dispatch
            // at graph depth is its own rung, the partial serves the
            // mapped leaves and missing ones stay loud below.
            TypedSpec emb = inner;
            var ow2 = com.legend.resolver.Substitution.otherwiseOf(b0);
            if (ow2 != null) {
                emb = ow2.args().get(0);
            }
            if (emb instanceof TypedNewInstance ctor2) {
                return embeddedChild(cs, node, ctor2, context);
            }
            throw new NotImplementedException("graph child '" + node.property()
                    + "' of class '" + cs.classFqn() + "' is mapped as an"
                    + " embedded/join-slot/otherwise/M2M binding — only"
                    + " association children are supported yet (H4b/H5c)");
        }
        AssociationJoins.AssocJoin aj = assocMaterial.associationJoin(temporal, cs, node.property(), context, /*forExists*/ true);
        var assoc = ctx.findAssociationOf(cs.classFqn(), node.property()).orElseThrow();
        var end = assoc.property1().propertyName().equals(node.property())
                ? assoc.property1() : assoc.property2();
        boolean toMany = !end.isToOne();
        return correlatedGraphChild(aj.target(), aj.targetPipeline(), aj.targetRow(),
                aj.condition(), toMany, node, parentRowVar, parentRowType, context);
    }

    /**
     * A graph child through a NAVIGATE SLOT: the slot's raw target composes
     * into the child's declared (possibly M2M) class; the slot predicate
     * λ(sourceRow, targetRow) correlates them.
     */
    TypedSerializeGraph.Child navSlotChild(ClassSource cs, TypedGraphTree node,
            TypedNavigate nav, String castClassFqn,
            StoreResolver.Context context, String parentRowVar,
            Type.RelationType parentRowType) {
        String key = (context.explicitMapping() == null ? "" : context.explicitMapping())
                + '\u0000'
                + (context.runtimeFqn() == null ? "" : context.runtimeFqn());
        String rawTarget = ((TypedGetAll) nav.target()).classFqn();
        var prop = ctx.findProperty(cs.classFqn(), node.property()).orElseThrow(
                () -> new IllegalStateException("resolver bug: graph child '"
                        + node.property() + "' is not a property of '"
                        + cs.classFqn() + "'"));
        String childClass = castClassFqn != null ? castClassFqn
                : prop.type() instanceof Type.ClassType cc
                        ? cc.fqn() : null;
        if (childClass == null) {
            throw new IllegalStateException("resolver bug: navigate-slot graph child '"
                    + node.property() + "' is not class-typed");
        }
        boolean toMany = !(prop.multiplicity()
                instanceof com.legend.compiler.element.type.Multiplicity.Bounded bm
                && Integer.valueOf(1).equals(bm.upper()));
        ClassSource child = childClass.equals(rawTarget)
                ? sources.get(dispatch.apply(context, rawTarget), rawTarget,
                        target -> dispatch.apply(context, target), key)
                : sources.get(cs.mappingFqn(), childClass,
                        target -> dispatch.apply(context, target), key);
        // The slot predicate's right side reads the RAW TARGET's physical
        // columns — the child's composed pipeline must bottom at that same
        // row or the correlation filters the wrong relation (audit: the
        // m2mAssocChild guard, applied to this sibling too).
        if (!childClass.equals(rawTarget)) {
            ClassSource rawSource = sources.get(dispatch.apply(context, rawTarget), rawTarget,
                    target -> dispatch.apply(context, target), key);
            if (!child.rowVar().equals(rawSource.rowVar())) {
                throw new NotImplementedException("navigate-slot graph child '"
                        + node.property() + "': the child class '" + childClass
                        + "' does not compose the slot target '" + rawTarget
                        + "' — cross-source children are not supported yet");
            }
        }
        Pipelines.Materialized cMat = Pipelines.materialize(
                child.pipeline(), Set.of(), childClass);
        return correlatedGraphChild(child, cMat.pipeline(),
                (Type.RelationType)
                        cMat.pipeline().info().type(),
                nav.predicate(), toMany, node, parentRowVar, parentRowType, context);
    }

    /**
     * An M2M child through the SOURCE class's association: the upstream
     * association supplies the condition and the RAW target; the child
     * serialized is the node property's DECLARED M2M class, whose composed
     * pipeline bottoms at that same raw target row (M2M composition
     * preserves the inner pipeline and row var — the correlation aligns by
     * construction, asserted loudly).
     */
    TypedSerializeGraph.Child m2mAssocChild(ClassSource cs, TypedGraphTree node,
            String srcClassFqn, String assocProp, StoreResolver.Context context,
            String parentRowVar,
            Type.RelationType parentRowType) {
        String key = (context.explicitMapping() == null ? "" : context.explicitMapping())
                + '\u0000'
                + (context.runtimeFqn() == null ? "" : context.runtimeFqn());
        ClassSource rawParent = sources.get(dispatch.apply(context, srcClassFqn), srcClassFqn,
                target -> dispatch.apply(context, target), key);
        AssociationJoins.AssocJoin aj = assocMaterial.associationJoin(temporal, rawParent, assocProp, context, /*forExists*/ true);
        var prop = ctx.findProperty(cs.classFqn(), node.property()).orElseThrow(
                () -> new IllegalStateException("resolver bug: graph child '"
                        + node.property() + "' is not a property of '"
                        + cs.classFqn() + "'"));
        // Cardinality from the DECLARED property — the spec the consumer
        // typed against — not the source association's end (consistent with
        // navSlotChild; audit).
        boolean toMany = !(prop.multiplicity()
                instanceof com.legend.compiler.element.type.Multiplicity.Bounded bm
                && Integer.valueOf(1).equals(bm.upper()));
        if (!(prop.type() instanceof Type.ClassType childCls)) {
            throw new IllegalStateException("resolver bug: M2M graph child '"
                    + node.property() + "' is not class-typed");
        }
        ClassSource child = sources.get(cs.mappingFqn(), childCls.fqn(),
                target -> dispatch.apply(context, target), key);
        if (!child.rowVar().equals(aj.target().rowVar())) {
            throw new NotImplementedException("M2M graph child '" + node.property()
                    + "': the child class '" + childCls.fqn() + "' does not compose"
                    + " the association target '" + aj.target().classFqn()
                    + "' — cross-source M2M children are not supported yet");
        }
        Pipelines.Materialized cMat = Pipelines.materialize(
                child.pipeline(), Set.of(), childCls.fqn());
        return correlatedGraphChild(child, cMat.pipeline(),
                (Type.RelationType)
                        cMat.pipeline().info().type(),
                aj.condition(), toMany, node, parentRowVar, parentRowType, context);
    }

    /**
     * The correlated-child tail shared by ASSOCIATION children and M2M
     * source-association children: the condition λ(parent, target) frees its
     * parent reads onto the enclosing row var, filters the target pipeline,
     * and the child's own envelope builds beneath.
     */
    TypedSerializeGraph.Child correlatedGraphChild(ClassSource target,
            TypedSpec targetPipeline,
            Type.RelationType targetRow,
            TypedLambda condition, boolean toMany, TypedGraphTree node,
            String parentRowVar,
            Type.RelationType parentRowType,
            StoreResolver.Context context) {
        // The association condition λ(parent, target): parent reads become
        // the FREE parent row var (the lowerer's enclosing-scope channel);
        // the target param stays as the child filter's own row.
        TypedLambda cond = condition;
        String pVar = cond.parameters().get(0);
        String tVar = cond.parameters().get(1);
        List<TypedSpec> corrBody = cond.body().stream().map(b ->
                Pipelines.rewriteRowReads(b, pVar, Map.of(), Set.of(),
                        v -> new TypedVariable(parentRowVar,
                                new ExprType(parentRowType,
                                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE))))
                .toList();
        TypedLambda corr = new TypedLambda(List.of(tVar), corrBody,
                new ExprType(
                        new Type.FunctionType(
                                List.of(new Type.Param(
                                        targetRow,
                                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                                new Type.Param(
                                        Type.Primitive.BOOLEAN,
                                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
        TypedSpec childRel = new TypedFilter(targetPipeline, corr,
                targetPipeline.info());
        Set<String> childParams = new LinkedHashSet<>();
        for (TypedSpec b : target.bindings().values()) {
            StoreResolver.collectLambdaParams(b, childParams);
        }
        childParams.addAll(cond.parameters());
        String childVar;
        do {
            childVar = "_r" + freshVar.getAsInt();
        } while (childParams.contains(childVar));
        var childInfo = new ExprType(
                new Type.ClassType(target.classFqn()),
                toMany ? com.legend.compiler.element.type.Multiplicity.Bounded.ZERO_MANY
                        : com.legend.compiler.element.type.Multiplicity.Bounded.ZERO_ONE);
        TypedSerializeGraph child = buildGraphNode(target, childRel, Map.of(),
                Pipelines.slotAliases(target.pipeline()), childVar,
                node.children(), context, toMany, childInfo);
        return new TypedSerializeGraph.Child(keyOf(node), child);
    }

    /** An EMBEDDED graph child: leaves come straight from the ^Inner
     * ctor (parent-row expressions), nested embedded ctors recurse as
     * further inline children. Non-ctor leaves (slot/assoc reads inside
     * the embedded) stay loud — their join machinery is a later rung. */
    private TypedSerializeGraph.Child embeddedChild(ClassSource cs,
            TypedGraphTree node, TypedNewInstance ctor,
            StoreResolver.Context context) {
        var prop = ctx.findProperty(cs.classFqn(), node.property())
                .orElseThrow(() -> new IllegalStateException(
                        "resolver bug: graph child '" + node.property()
                        + "' is not a property of '" + cs.classFqn() + "'"));
        String childClass = prop.type() instanceof Type.ClassType cc
                ? cc.fqn() : cs.classFqn();
        List<TypedGraphTree> want = node.children().isEmpty()
                ? ctor.properties().keySet().stream()
                        .map(k -> new TypedGraphTree(k, List.of()))
                        .toList()
                : node.children();
        Type.RelationType rowT = (Type.RelationType)
                cs.pipeline().info().type();
        var rowInfo = new ExprType(rowT,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        List<TypedFuncCol> leaves = new ArrayList<>();
        List<TypedSerializeGraph.Child> nested = new ArrayList<>();
        for (TypedGraphTree c : want) {
            TypedSpec e = ctor.properties().get(c.property());
            if (e == null) {
                // DERIVED property of the EMBEDDED class: the lifted body
                // inlines against the ctor's bindings — parent-row exprs,
                // same machinery as top-level qualifier leaves (task #78)
                TypedSpec dv = derivedLeaf(ctor.properties(), childClass, c);
                if (dv != null) {
                    var dFn2 = new Type.FunctionType(
                            List.of(new Type.Param(rowT,
                                    com.legend.compiler.element.type
                                            .Multiplicity.Bounded.ONE)),
                            new Type.Param(dv.info().type(),
                                    dv.info().multiplicity()));
                    leaves.add(new TypedFuncCol(c.alias() != null
                            ? c.alias() : callKey(c),
                            new TypedLambda(List.of(cs.rowVar()),
                                    List.of(dv),
                                    new ExprType(dFn2,
                                            com.legend.compiler.element.type
                                                    .Multiplicity.Bounded
                                                    .ONE))));
                    continue;
                }
                throw new MappingResolutionException("property '"
                        + c.property() + "' of embedded '" + node.property()
                        + "' on class '" + cs.classFqn()
                        + "' is not mapped in mapping '" + cs.mappingFqn()
                        + "'", cs.classFqn());
            }
            TypedSpec ei = e;
            if (ei instanceof TypedNativeCall tc1 && tc1.args().size() == 1
                    && tc1.callee().qualifiedName().equals(
                            "meta::pure::functions::multiplicity::toOne")) {
                ei = tc1.args().get(0);
            }
            if (ei instanceof TypedNewInstance subCtor) {
                nested.add(embeddedChild(cs, c, subCtor, context));
                continue;
            }
            if (ei.info().type() instanceof Type.ClassType) {
                throw new NotImplementedException("embedded graph child '"
                        + node.property() + "." + c.property()
                        + "' is class-typed through a non-ctor binding —"
                        + " not supported yet");
            }
            var lFn = new Type.FunctionType(
                    List.of(new Type.Param(rowT,
                            com.legend.compiler.element.type.Multiplicity
                                    .Bounded.ONE)),
                    new Type.Param(e.info().type(), e.info().multiplicity()));
            leaves.add(new TypedFuncCol(keyOf(c),
                    new TypedLambda(List.of(cs.rowVar()), List.of(e),
                            new ExprType(lFn,
                                    com.legend.compiler.element.type
                                            .Multiplicity.Bounded.ONE))));
        }
        TypedSerializeGraph nodeG = new TypedSerializeGraph(cs.pipeline(),
                cs.rowVar(), leaves, nested, false, false, childClass,
                rowInfo, true);
        return new TypedSerializeGraph.Child(keyOf(node), nodeG);
    }

    /** The INLINED typed body of a parameterless derived property, its
     * {@code $this} reads substituted to the class's row bindings —
     * null when the name is not a parameterless derived property. */
    private TypedSpec derivedLeaf(ClassSource cs, TypedGraphTree node,
            StoreResolver.Context context, String rowVar,
            Type.RelationType rowType) {
        String prop = node.property();
        var p = ctx.findProperty(cs.classFqn(), prop).orElse(null);
        if (!(p instanceof com.legend.compiler.element.Property.Derived d)
                || d.parameters().size() != node.args().size()) {
            return null;
        }
        var cf = sources.compileSynthFn(d.bodyFunctionFqn());
        if (cf.body().size() == 1) {
            String thisVar = cf.signature().parameters().get(0).name();
            Map<String, TypedSpec> binds = new java.util.LinkedHashMap<>();
            for (int i = 0; i < node.args().size(); i++) {
                binds.put(cf.signature().parameters().get(i + 1).name(),
                        node.args().get(i));
            }
            TypedSpec nav = navLeafSubquery(cs,
                    substVars(cf.body().get(0), binds), thisVar,
                    context, rowVar, rowType);
            if (nav != null) {
                return nav;
            }
        }
        return derivedLeaf(cs.bindings(), cs.classFqn(), node);
    }

    /**
     * A derived body that is a DEPTH-1 NAVIGATION with a scalar leaf —
     * {@code $this.assoc.leaf} behind optional toOne/first wrappers —
     * emits as the fnlr encoding: the target pipeline filtered by the
     * oriented association condition (parent reads freed onto the
     * enclosing row var), projecting the leaf binding, LIMIT 1 — the
     * lowerer renders a relation in scalar position as a correlated
     * scalar subquery. Null when the shape does not match (the inline
     * route and its louder walls take over).
     */
    private TypedSpec navLeafSubquery(ClassSource cs, TypedSpec body,
            String thisVar, StoreResolver.Context context, String parentRowVar,
            Type.RelationType parentRowType) {
        TypedSpec b = body;
        while (b instanceof TypedNativeCall w && w.args().size() == 1
                && (w.callee().qualifiedName().equals(
                        "meta::pure::functions::multiplicity::toOne")
                    || w.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::first"))) {
            b = w.args().get(0);
        }
        if (!(b instanceof TypedPropertyAccess leaf)) {
            return null;
        }
        TypedSpec hop = leaf.source();
        while (hop instanceof TypedNativeCall w2 && w2.args().size() == 1
                && (w2.callee().qualifiedName().equals(
                        "meta::pure::functions::multiplicity::toOne")
                    || w2.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::first"))) {
            hop = w2.args().get(0);
        }
        // a NESTED $prop$ qualifier CALL β-inlines here (compileSynthFn
        // keeps user calls: $this.synonymByType(CUSIP) arrives as a
        // TypedUserCall whose callee body is the filter shape below)
        if (hop instanceof com.legend.compiler.spec.typed.TypedUserCall uc
                && uc.callee().qualifiedName().contains("$prop$")) {
            var ccf = sources.compileSynthFn(uc.callee().qualifiedName());
            if (ccf.body().size() == 1
                    && ccf.signature().parameters().size() == uc.args().size()) {
                Map<String, TypedSpec> sub = new java.util.LinkedHashMap<>();
                for (int i = 0; i < uc.args().size(); i++) {
                    sub.put(ccf.signature().parameters().get(i).name(),
                            uc.args().get(i));
                }
                hop = substVars(ccf.body().get(0), sub);
                while (hop instanceof TypedNativeCall w4
                        && w4.args().size() == 1
                        && (w4.callee().qualifiedName().equals(
                                "meta::pure::functions::multiplicity::toOne")
                            || w4.callee().qualifiedName().equals(
                                "meta::pure::functions::collection::first"))) {
                    hop = w4.args().get(0);
                }
            }
        }
        // a FILTERED head (the qualifier-calls-qualifier inline:
        // toOne(filter($this.synonyms, pred)).name) carries its predicate
        // into the subquery, substituted through the TARGET's bindings
        TypedLambda extraPred = null;
        if (hop instanceof TypedFilter hf
                && hf.predicate().parameters().size() == 1
                && hf.predicate().body().size() == 1) {
            TypedSpec inner = hf.source();
            while (inner instanceof TypedNativeCall w3 && w3.args().size() == 1
                    && (w3.callee().qualifiedName().equals(
                            "meta::pure::functions::multiplicity::toOne")
                        || w3.callee().qualifiedName().equals(
                            "meta::pure::functions::collection::first"))) {
                inner = w3.args().get(0);
            }
            extraPred = hf.predicate();
            hop = inner;
        }
        String headProp;
        List<TypedSpec> hopDates = List.of();
        boolean hopSweep = false;
        if (hop instanceof TypedPropertyAccess hpa
                && hpa.source() instanceof TypedVariable hv
                && hv.name().equals(thisVar)
                && hpa.info().type() instanceof Type.ClassType) {
            headProp = hpa.property();
        } else if (hop instanceof com.legend.compiler.spec.typed
                        .TypedMilestonedAccess hma
                && hma.source() instanceof TypedVariable hv2
                && hv2.name().equals(thisVar)) {
            // a DATED head registers its dates as the head's temporal spec
            // (the same channel query-position property functions use)
            headProp = hma.property();
            hopDates = hma.dates();
            hopSweep = hma.sweep();
        } else {
            return null;
        }
        ClassSource target;
        TypedSpec targetPipeline;
        Type.RelationType targetRow;
        TypedLambda cond;
        TemporalFrame tf = hopDates.isEmpty() && !hopSweep ? temporal
                : temporal.withSpecs(Map.of(headProp,
                        new TemporalFrame.TemporalSpec(hopDates, hopSweep)));
        AssociationJoins.AssocJoin aj = null;
        try {
            aj = assocMaterial.associationJoin(tf, cs, headProp,
                    context, /*forExists*/ true);
        } catch (RuntimeException notAnAssoc) {
            aj = null;
        }
        if (aj != null) {
            target = aj.target();
            targetPipeline = aj.targetPipeline();
            targetRow = aj.targetRow();
            cond = aj.condition();
        } else {
            // NAVIGATE-SLOT-backed head (join PM): the slot's TypedNavigate
            // carries the raw target and the predicate — navSlotChild's
            // route, scalar-shaped
            TypedSpec bindingRead = cs.bindings().get(headProp);
            // conform-by-emission wrappers (toOne over the slot read) unwrap
            while (bindingRead instanceof TypedNativeCall bw
                    && bw.args().size() == 1
                    && (bw.callee().qualifiedName().equals(
                            "meta::pure::functions::multiplicity::toOne")
                        || bw.callee().qualifiedName().equals(
                            "meta::pure::functions::collection::first"))) {
                bindingRead = bw.args().get(0);
            }
            TypedNavigate nav = null;
            if (bindingRead instanceof TypedPropertyAccess bpa
                    && bpa.source() instanceof TypedVariable bvv
                    && bvv.name().equals(cs.rowVar())) {
                nav = Pipelines.navSteps(cs.pipeline()).get(bpa.property());
            }
            if (nav == null) {
                return null;
            }
            String key = (context.explicitMapping() == null ? ""
                    : context.explicitMapping()) + '\u0000'
                    + (context.runtimeFqn() == null ? ""
                            : context.runtimeFqn());
            String rawTarget = ((TypedGetAll) nav.target()).classFqn();
            target = sources.get(dispatch.apply(context, rawTarget), rawTarget,
                    t -> dispatch.apply(context, t), key);
            Pipelines.Materialized cMat = Pipelines.materialize(
                    target.pipeline(), Set.of(), rawTarget);
            targetPipeline = tf.temporalTargetPipe(cs, target, headProp,
                    cMat.pipeline());
            targetRow = (Type.RelationType) targetPipeline.info().type();
            cond = nav.predicate();
        }
        TypedSpec leafBind = target.bindings().get(leaf.property());
        if (leafBind == null
                || leafBind.info().type() instanceof Type.ClassType) {
            return null;
        }
        String pVar = cond.parameters().get(0);
        String tVar = cond.parameters().get(1);
        List<TypedSpec> corrBody = cond.body().stream().map(cb ->
                Pipelines.rewriteRowReads(cb, pVar, Map.of(), Set.of(),
                        v -> new TypedVariable(parentRowVar,
                                new ExprType(parentRowType,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE))))
                .toList();
        var boolFn = new Type.FunctionType(
                List.of(new Type.Param(targetRow,
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                new Type.Param(Type.Primitive.BOOLEAN,
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
        TypedLambda corr = new TypedLambda(List.of(tVar), corrBody,
                new ExprType(boolFn,
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
        TypedSpec rel = new TypedFilter(targetPipeline, corr,
                targetPipeline.info());
        if (extraPred != null) {
            TypedSpec pb;
            try {
                pb = inlineThis(extraPred.body().get(0),
                        extraPred.parameters().get(0), Map.of(),
                        target.bindings(), target.classFqn(),
                        leaf.property());
            } catch (NotImplementedException unsupported) {
                return null;   // the inline route's louder wall takes over
            }
            rel = new TypedFilter(rel,
                    new TypedLambda(List.of(target.rowVar()),
                            List.of(pb), corr.info()),
                    rel.info());
        }
        // project the leaf binding over the TARGET's own row var
        var leafFn = new Type.FunctionType(
                List.of(new Type.Param(targetRow,
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                new Type.Param(leafBind.info().type(),
                        leafBind.info().multiplicity()));
        Type.RelationType oneCol = new Type.RelationType(List.of(
                new Type.Column(leaf.property(), leafBind.info().type(),
                        leafBind.info().multiplicity())));
        TypedSpec proj = new com.legend.compiler.spec.typed.TypedProject(rel,
                List.of(new TypedFuncCol(leaf.property(),
                        new TypedLambda(List.of(target.rowVar()),
                                List.of(leafBind),
                                new ExprType(leafFn,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE)))),
                new ExprType(oneCol,
                        com.legend.compiler.element.type
                                .Multiplicity.Bounded.ZERO_MANY));
        // the READ is [0..1]: pure toOne semantics — a plain scalar
        // subquery, never a LIST aggregation
        return new com.legend.compiler.spec.typed.TypedLimit(proj,
                new com.legend.compiler.spec.typed.TypedCInteger(1L,
                        ExprType.one(Type.Primitive.INTEGER)),
                new ExprType(oneCol,
                        com.legend.compiler.element.type
                                .Multiplicity.Bounded.ZERO_ONE));
    }

    private TypedSpec derivedLeaf(Map<String, TypedSpec> bindings,
            String classFqn, TypedGraphTree node) {
        String prop = node.property();
        var p = ctx.findProperty(classFqn, prop).orElse(null);
        if (!(p instanceof com.legend.compiler.element.Property.Derived d)
                || d.parameters().size() != node.args().size()) {
            return null;
        }
        var cf = sources.compileSynthFn(d.bodyFunctionFqn());
        if (cf.body().size() != 1) {
            throw new NotImplementedException("derived graph leaf '" + prop
                    + "' has a multi-statement body — not supported yet");
        }
        // lifted signature = (this, <declared params>...) — call args
        // bind positionally after $this (tree args are typed literals)
        String thisVar = cf.signature().parameters().get(0).name();
        Map<String, TypedSpec> binds = new java.util.LinkedHashMap<>();
        for (int i = 0; i < node.args().size(); i++) {
            binds.put(cf.signature().parameters().get(i + 1).name(),
                    node.args().get(i));
        }
        return inlineThis(cf.body().get(0), thisVar, binds, bindings,
                classFqn, prop);
    }

    /** Substitute {@code $this.<stored>} reads with the row bindings.
     * Rebuild arms cover the expression shapes derived bodies produce;
     * an UNHANDLED node still CONTAINING {@code $this} throws loud —
     * never silent (an unreplaced var would only error later at the
     * lowering, far from its cause). */
    private TypedSpec inlineThis(TypedSpec n, String thisVar,
            Map<String, TypedSpec> binds, Map<String, TypedSpec> bindings,
            String classFqn, String prop) {
        if (n instanceof TypedVariable bv && binds.containsKey(bv.name())) {
            return binds.get(bv.name());
        }
        if (n instanceof TypedPropertyAccess pa
                && pa.source() instanceof TypedVariable v
                && v.name().equals(thisVar)) {
            TypedSpec b = bindings.get(pa.property());
            if (b == null || b.info().type() instanceof Type.ClassType) {
                throw new NotImplementedException("derived graph leaf '"
                        + prop + "' reads '" + pa.property() + "' which is "
                        + (b == null ? "not a stored binding"
                                : "class-typed (navigation)")
                        + " on '" + classFqn + "' — only same-class"
                        + " stored reads inline yet");
            }
            return b;
        }
        if (n instanceof TypedVariable v && v.name().equals(thisVar)) {
            throw new NotImplementedException("derived graph leaf '" + prop
                    + "' uses $" + thisVar + " as a whole value —"
                    + " not supported yet");
        }
        if (n instanceof TypedNativeCall c) {
            List<TypedSpec> args = new ArrayList<>(c.args().size());
            for (TypedSpec a : c.args()) {
                args.add(inlineThis(a, thisVar, binds, bindings, classFqn, prop));
            }
            return new TypedNativeCall(c.callee(), args, c.info());
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedIf i) {
            return new com.legend.compiler.spec.typed.TypedIf(
                    inlineThis(i.condition(), thisVar, binds, bindings, classFqn, prop),
                    inlineThis(i.thenBranch(), thisVar, binds, bindings, classFqn, prop),
                    i.elseBranch().map(e ->
                            inlineThis(e, thisVar, binds, bindings, classFqn, prop)),
                    n.info());
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedCollection tc) {
            List<TypedSpec> els = new ArrayList<>(tc.elements().size());
            for (TypedSpec e : tc.elements()) {
                els.add(inlineThis(e, thisVar, binds, bindings, classFqn, prop));
            }
            return new com.legend.compiler.spec.typed.TypedCollection(
                    els, tc.info());
        }
        if (containsVar(n, thisVar)) {
            throw new NotImplementedException("derived graph leaf '" + prop
                    + "' body node " + n.getClass().getSimpleName()
                    + " referencing $" + thisVar
                    + " is not inlinable yet");
        }
        return n;
    }

    /** The engine's key for a non-aliased qualifier leaf: the SOURCE
     * call spelling — {@code fullName(false)}, {@code name()}. Literal
     * args render in pure form; non-literal args require an alias
     * (loud). */
    private static String callKey(TypedGraphTree node) {
        StringBuilder k = new StringBuilder(node.property()).append('(');
        for (int i = 0; i < node.args().size(); i++) {
            if (i > 0) {
                k.append(", ");
            }
            TypedSpec a = node.args().get(i);
            if (a instanceof com.legend.compiler.spec.typed.TypedCBoolean b) {
                k.append(b.value());
            } else if (a instanceof
                    com.legend.compiler.spec.typed.TypedCInteger ci) {
                k.append(ci.value());
            } else if (a instanceof
                    com.legend.compiler.spec.typed.TypedCString cstr) {
                k.append('\'').append(cstr.value()).append('\'');
            } else if (a instanceof
                    com.legend.compiler.spec.typed.TypedCDate cd) {
                // engine prints the date VALUE with an explicit +0000
                // zone when it carries a time component
                String d = cd.value().toEngineString();
                k.append(d).append(d.indexOf('T') >= 0 ? "+0000" : "");
            } else {
                throw new NotImplementedException("parameterized qualifier"
                        + " tree leaf '" + node.property() + "' with a"
                        + " non-literal argument needs an alias — the"
                        + " rendered-key form only covers literals");
            }
        }
        return k.append(')').toString();
    }

    /**
     * A ->subType(@X){...} node as a member-gated patch: scalar leaves read
     * the row's stc carrier columns (NULL off-member); class-typed children
     * correlate against the SUBTYPE's own ClassSource; the $member witness
     * gates the branch at the envelope.
     */
    private TypedSerializeGraph.SubTypePatch subTypePatch(ClassSource cs,
            TypedGraphTree node, StoreResolver.Context context, String rowVar,
            Type.RelationType rowType,
            UnaryOperator<TypedSpec> toRow) {
        List<TypedFuncCol> patch = new ArrayList<>();
        List<TypedSerializeGraph.Child> patchChildren = new ArrayList<>();
        for (TypedGraphTree sub : node.children()) {
            if (!sub.children().isEmpty()) {
                // CLASS-typed subtype child (coordinate{...}): a correlated
                // child against the SUBTYPE's own ClassSource (its member
                // pipeline holds the binding); it rides INSIDE the
                // member-gated branch, so non-member rows never emit it
                String skey = (context.explicitMapping() == null ? ""
                        : context.explicitMapping()) + '\u0000'
                        + (context.runtimeFqn() == null ? ""
                                : context.runtimeFqn());
                ClassSource subCs = sources.get(cs.mappingFqn(),
                        node.subTypeFqn(),
                        target -> dispatch.apply(context, target), skey);
                patchChildren.add(graphChild(subCs, sub, context,
                        rowVar, rowType));
                continue;
            }
            String col = com.legend.model.ClassMapping.subTypeColumn(
                    node.subTypeFqn(), sub.property());
            Type.Column rc = rowType.columns().stream()
                    .filter(c -> c.name().equals(col))
                    .findFirst().orElse(null);
            if (rc == null) {
                throw new NotImplementedException("graph ->subType(@"
                        + node.subTypeFqn() + "): carrier column '"
                        + col + "' is not on the row (non-union"
                        + " subtype mapping) — not built yet");
            }
            TypedSpec read = new TypedPropertyAccess(
                    toRow.apply(null), col,
                    new ExprType(rc.type(), rc.multiplicity()));
            var pFn = new Type.FunctionType(
                    List.of(new Type.Param(rowType,
                            com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ONE)),
                    new Type.Param(rc.type(), rc.multiplicity()));
            patch.add(new TypedFuncCol(keyOf(sub),
                    new TypedLambda(List.of(rowVar), List.of(read),
                            new ExprType(pFn,
                                    com.legend.compiler.element.type
                                            .Multiplicity.Bounded.ONE))));
        }
        String mcol = com.legend.model.ClassMapping.subTypeColumn(
                node.subTypeFqn(),
                com.legend.model.ClassMapping.memberWitness());
        Type.Column mrc = rowType.columns().stream()
                .filter(c -> c.name().equals(mcol))
                .findFirst().orElseThrow(() ->
                        new NotImplementedException("graph ->subType(@"
                                + node.subTypeFqn() + "): membership"
                                + " witness '" + mcol
                                + "' is not on the row"));
        TypedSpec mread = new TypedPropertyAccess(
                toRow.apply(null), mcol,
                new ExprType(mrc.type(), mrc.multiplicity()));
        var mFn = new Type.FunctionType(
                List.of(new Type.Param(rowType,
                        com.legend.compiler.element.type
                                .Multiplicity.Bounded.ONE)),
                new Type.Param(mrc.type(), mrc.multiplicity()));
        TypedFuncCol member = new TypedFuncCol(mcol,
                new TypedLambda(List.of(rowVar), List.of(mread),
                        new ExprType(mFn,
                                com.legend.compiler.element.type
                                        .Multiplicity.Bounded.ONE)));
        return new TypedSerializeGraph.SubTypePatch(
                node.subTypeFqn(), patch, member, patchChildren);
    }

    /** The supported serialize-config surface: includeType (+ typeKeyName,
     * fullyQualifiedTypePath) emit the type key; every OTHER envelope-
     * changing flag walls loudly — never a silently-ignored config. */
    record SerializeTypeConfig(String typeKey, boolean fq) {
    }

    static SerializeTypeConfig serializeTypeConfig(TypedSpec cfg) {
        if (!(cfg instanceof com.legend.compiler.spec.typed.TypedNewInstance ni)
                || !ni.classFqn().endsWith("AlloySerializationConfig")) {
            throw new NotImplementedException("serialize config of shape "
                    + cfg.getClass().getSimpleName() + " is not supported yet");
        }
        boolean includeType = false;
        boolean fq = false;
        String key = "@type";
        for (var e : ni.properties().entrySet()) {
            TypedSpec v = e.getValue();
            switch (e.getKey()) {
                case "typeKeyName" -> {
                    if (v instanceof com.legend.compiler.spec.typed.TypedCString cs) {
                        key = cs.value();
                    }
                }
                case "includeType" -> includeType =
                        v instanceof com.legend.compiler.spec.typed
                                .TypedCBoolean b && b.value();
                case "fullyQualifiedTypePath" -> fq =
                        v instanceof com.legend.compiler.spec.typed
                                .TypedCBoolean b && b.value();
                default -> {
                    boolean nop = v instanceof com.legend.compiler.spec.typed
                            .TypedCBoolean b && !b.value();
                    if (!nop) {
                        throw new NotImplementedException("serialize config"
                                + " flag '" + e.getKey() + "' is not"
                                + " supported yet");
                    }
                }
            }
        }
        return includeType ? new SerializeTypeConfig(key, fq) : null;
    }

    /** Stamp the type-key config on EVERY node of a built envelope
     * (nested + patch children) — one application at the serialize arm. */
    static TypedSerializeGraph withTypeKey(TypedSerializeGraph g,
            SerializeTypeConfig c) {
        return new TypedSerializeGraph(g.source(), g.rowVar(), g.leaves(),
                g.nested().stream().map(ch -> new TypedSerializeGraph.Child(
                        ch.property(), withTypeKey(ch.node(), c))).toList(),
                g.arrayWrap(), g.bareValue(), g.classFqn(), g.info(),
                g.inlineChild(),
                g.subTypePatches().stream().map(p ->
                        new TypedSerializeGraph.SubTypePatch(p.subTypeFqn(),
                                p.leaves(), p.member(),
                                p.children().stream().map(ch ->
                                        new TypedSerializeGraph.Child(
                                                ch.property(),
                                                withTypeKey(ch.node(), c)))
                                        .toList()))
                        .toList(),
                g.orderKeys(), c.typeKey(), c.fq());
    }

    /** β-substitute free variables by name over the derived-body
     * vocabulary (shadowing lambdas drop their params). */
    private static TypedSpec substVars(TypedSpec n, Map<String, TypedSpec> sub) {
        if (sub.isEmpty()) {
            return n;
        }
        return switch (n) {
            case TypedVariable v ->
                    sub.getOrDefault(v.name(), v);
            case TypedPropertyAccess pa -> new TypedPropertyAccess(
                    substVars(pa.source(), sub), pa.property(), pa.info());
            case com.legend.compiler.spec.typed.TypedMilestonedAccess ma ->
                    new com.legend.compiler.spec.typed.TypedMilestonedAccess(
                            substVars(ma.source(), sub), ma.property(),
                            ma.dates().stream().map(d2 -> substVars(d2, sub))
                                    .toList(),
                            ma.sweep(), ma.info());
            case TypedNativeCall c -> new TypedNativeCall(c.callee(),
                    c.args().stream().map(a -> substVars(a, sub)).toList(),
                    c.info());
            case TypedFilter f -> new TypedFilter(substVars(f.source(), sub),
                    (TypedLambda) substVars(f.predicate(), sub), f.info());
            case TypedLambda l -> {
                Map<String, TypedSpec> inner = new java.util.LinkedHashMap<>(sub);
                l.parameters().forEach(inner::remove);
                yield new TypedLambda(l.parameters(),
                        l.body().stream().map(b2 -> substVars(b2, inner))
                                .toList(), l.info());
            }
            case com.legend.compiler.spec.typed.TypedIf i ->
                    new com.legend.compiler.spec.typed.TypedIf(
                            substVars(i.condition(), sub),
                            substVars(i.thenBranch(), sub),
                            i.elseBranch().map(e -> substVars(e, sub)),
                            i.info());
            case com.legend.compiler.spec.typed.TypedCollection tc ->
                    new com.legend.compiler.spec.typed.TypedCollection(
                            tc.elements().stream()
                                    .map(e -> substVars(e, sub)).toList(),
                            tc.info());
            default -> n;
        };
    }

    /** The serialized key: the tree alias when given, else the
     * property name. */
    private static String keyOf(TypedGraphTree node) {
        if (node.alias() != null) {
            return node.alias();
        }
        return node.args().isEmpty() ? node.property() : callKey(node);
    }

    private static boolean containsVar(TypedSpec n, String var) {
        if (n instanceof TypedVariable v && v.name().equals(var)) {
            return true;
        }
        for (TypedSpec c : n.children()) {
            if (containsVar(c, var)) {
                return true;
            }
        }
        return false;
    }
}
