// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.MilestoningStrategy;
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
        MilestoningStrategy strat = temporal.temporalStrategy(cs.classFqn());
        if (strat != null) {
            if (strat != MilestoningStrategy.PROCESSING
                    && !cs.bindings().containsKey("businessDate")) {
                tree.add(new TypedGraphTree("businessDate", List.of()));
            }
            if (strat != MilestoningStrategy.BUSINESS
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
        return buildGraphNode(cs, pipeline, slotPrefixes, stripped, rowVar,
                tree, context, arrayWrap, info, false);
    }

    TypedSerializeGraph buildGraphNode(ClassSource cs, TypedSpec pipeline,
            Map<String, String> slotPrefixes, Set<String> stripped, String rowVar,
            List<TypedGraphTree> tree, StoreResolver.Context context, boolean arrayWrap,
            ExprType info, boolean checked) {
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
                children.add(graphChild(cs, node, context, rowVar, rowType, pipeline));
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
                    // an UNMAPPED OPTIONAL property serializes as null
                    // (engine graph semantics: milestoningModel's
                    // inlinedExchangeName rides unmapped in milestoningmap
                    // and the golden emits null); REQUIRED stays loud
                    var mp = ctx.findProperty(cs.classFqn(), node.property())
                            .orElse(null);
                    if (mp != null && mp.multiplicity()
                            instanceof com.legend.compiler.element.type
                                    .Multiplicity.Bounded ob
                            && ob.lower() == 0) {
                        gen = new com.legend.compiler.spec.typed
                                .TypedCollection(List.of(),
                                        new ExprType(mp.type(),
                                                com.legend.compiler.element
                                                        .type.Multiplicity
                                                        .Bounded.ZERO_ONE));
                    }
                }
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
                var navPrim = Pipelines.outerNavSteps(cs.pipeline()).get(slotPa.property());
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
                        Pipelines.outerNavSteps(cs.pipeline()).keySet());
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
            // AGG-CONSUMED reads of STRIPPED to-many nav steps (the H4b
            // sub-aggregation leaf: employeeCount = count($row.employees)
            // through the M2M chain composition): the class-typed slot
            // read becomes the CORRELATED TARGET RELATION — the nav
            // condition keyed on the parent row var — and count/size/
            // isEmpty lower through the relation-predicate scalar arms as
            // correlated subqueries (engine: per-leaf subselect). Only
            // STRIPPED aliases rewrite; a materialized slot keeps the
            // joined-scalar path.
            TypedSpec navCorr = correlateStrippedNavReads(binding, cs,
                    rowVar, rowType, stripped, context);
            if (navCorr != null) {
                // ALREADY in final row terms (the walker re-points parent
                // reads at the leaf row var) — the rewriteRowReads pass
                // below must not walk the embedded relation
                leaves.add(aggLeaf(keyOf(node), navCorr, rowVar, rowType));
                continue;
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
            // the leaf's RESULT type is the MODEL property's for the
            // DATE family: DateTime -> full instant, StrictDate -> bare,
            // abstract Date -> by the PHYSICAL value's precision (the
            // wrap dispatches at runtime; setup DDL can diverge from
            // the store declaration the binding is typed by)
            Type resultType = body.info().type();
            var mprop = ctx.findProperty(cs.classFqn(), node.property())
                    .orElse(null);
            if (mprop != null && isDateFamily(mprop.type())
                    && isDateFamily(resultType)) {
                resultType = mprop.type();
            }
            var fnType = new Type.FunctionType(
                    List.of(new Type.Param(rowType,
                            com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                    new Type.Param(
                            resultType, body.info().multiplicity()));
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
        TypedSerializeGraph node = new TypedSerializeGraph(pipeline, rowVar,
                leaves, children, arrayWrap, false, cs.classFqn(), info,
                false, subTypePatches, orderKeys);
        return checked ? withChecked(node, cs, slotPrefixes, stripped,
                rowVar, rowType, context, toRow) : node;
    }

    private TypedSerializeGraph withChecked(TypedSerializeGraph node,
            ClassSource cs, Map<String, String> slotPrefixes,
            Set<String> stripped, String rowVar, Type.RelationType rowType,
            StoreResolver.Context context, UnaryOperator<TypedSpec> toRow) {
        return new TypedSerializeGraph(node.source(), node.rowVar(),
                node.leaves(), node.nested(), node.arrayWrap(),
                node.bareValue(), node.classFqn(), node.info(),
                node.inlineChild(), node.subTypePatches(), node.orderKeys(),
                node.typeKeyName(), node.fqTypePath(),
                checkedConstraints(cs, slotPrefixes, stripped, rowVar,
                        rowType, context, toRow));
    }

    /** The CHECKED envelope's constraint set — the class hierarchy's
     * constraints (own first, then supertypes'; the engine's
     * allConstraintsInHierarchy walk), each predicate/message inlined
     * through the row bindings like any leaf. The DEFINER class rides as
     * {@code ruleDefinerPath}; a constraint without {@code ~message}
     * takes the engine's default spelling (flatdata checked goldens). */
    private java.util.List<TypedSerializeGraph.CheckedConstraint>
            checkedConstraints(ClassSource cs,
            Map<String, String> slotPrefixes, Set<String> stripped,
            String rowVar, Type.RelationType rowType,
            StoreResolver.Context context, UnaryOperator<TypedSpec> toRow) {
        java.util.List<TypedSerializeGraph.CheckedConstraint> out =
                new ArrayList<>();
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        work.add(cs.classFqn());
        while (!work.isEmpty()) {
            String fqn = work.poll();
            if (!seen.add(fqn)) {
                continue;
            }
            var tc = ctx.findClass(fqn).orElse(null);
            if (tc == null) {
                continue;
            }
            for (var con : tc.constraints()) {
                out.add(new TypedSerializeGraph.CheckedConstraint(con.name(),
                        constraintFnCol("constraint$" + con.name(),
                                con.predicateFqn(), cs, slotPrefixes,
                                stripped, rowVar, rowType, context, toRow),
                        con.messageFqn().isPresent()
                                ? constraintFnCol("constraintMsg$"
                                        + con.name(), con.messageFqn().get(),
                                        cs, slotPrefixes, stripped, rowVar,
                                        rowType, context, toRow)
                                : constMsgCol(con.name(), fqn, rowVar,
                                        rowType),
                        con.level() == com.legend.compiler.element
                                .TypedConstraint.EnforcementLevel.WARN
                                ? "Warn" : "Error",
                        fqn));
            }
            work.addAll(tc.superClassFqns());
        }
        return out;
    }

    /** One constraint body (predicate or message fn) inlined through the
     * bindings and re-pointed at the envelope row. */
    private TypedFuncCol constraintFnCol(String name, String bodyFqn,
            ClassSource cs, Map<String, String> slotPrefixes,
            Set<String> stripped, String rowVar, Type.RelationType rowType,
            StoreResolver.Context context, UnaryOperator<TypedSpec> toRow) {
        var cf = sources.compileSynthFn(bodyFqn);
        String thisVar = cf.signature().parameters().get(0).name();
        TypedSpec body = inlineThis(cf.body().get(cf.body().size() - 1),
                thisVar, java.util.Map.of(), cs.bindings(), cs.classFqn(),
                name, new SubqueryEnv(cs, context, rowVar, rowType));
        body = Pipelines.rewriteRowReads(body, cs.rowVar(), slotPrefixes,
                stripped, toRow);
        return rowFnCol(name, body, rowVar, rowType);
    }

    /** The engine's DEFAULT constraint message (no {@code ~message}). */
    private static TypedFuncCol constMsgCol(String id, String definerFqn,
            String rowVar, Type.RelationType rowType) {
        String simple = definerFqn.substring(
                definerFqn.lastIndexOf(':') + 1);
        TypedSpec msg = new com.legend.compiler.spec.typed.TypedCString(
                "Constraint :[" + id + "] violated in the Class " + simple,
                ExprType.one(Type.Primitive.STRING));
        return rowFnCol("constraintMsg$" + id, msg, rowVar, rowType);
    }

    private static TypedFuncCol rowFnCol(String name, TypedSpec body,
            String rowVar, Type.RelationType rowType) {
        var fn = new Type.FunctionType(
                java.util.List.of(new Type.Param(rowType,
                        com.legend.compiler.element.type.Multiplicity
                                .Bounded.ONE)),
                new Type.Param(body.info().type(),
                        body.info().multiplicity()));
        return new TypedFuncCol(name, new TypedLambda(
                java.util.List.of(rowVar), java.util.List.of(body),
                new ExprType(fn, com.legend.compiler.element.type
                        .Multiplicity.Bounded.ONE)));
    }

    /** A leaf whose body is ALREADY in final row terms (the correlated
     * sub-aggregation emission) — plain lambda wrap, no row-read pass. */
    private TypedFuncCol aggLeaf(String key, TypedSpec body, String rowVar,
            Type.RelationType rowType) {
        var one = com.legend.compiler.element.type.Multiplicity.Bounded.ONE;
        var fnT = new Type.FunctionType(
                List.of(new Type.Param(rowType, one)),
                new Type.Param(body.info().type(), body.info().multiplicity()));
        return new TypedFuncCol(key, new TypedLambda(List.of(rowVar),
                List.of(body), new ExprType(fnT, one)));
    }

    /**
     * Rewrite reads of STRIPPED class-typed nav steps ({@code $row.<alias>})
     * inside a leaf binding to the CORRELATED TARGET RELATION: the step's
     * target ClassSource materialized (empty demand), filtered by the nav
     * condition with the parent-side reads re-pointed at {@code rowVar}.
     * Null when nothing rewrote (unchanged bindings keep their own walls).
     * Temporal targets stay un-rewritten (the per-hop date calculus does
     * not thread here) — their reads keep the loud H4b wall.
     */
    private TypedSpec correlateStrippedNavReads(TypedSpec n, ClassSource cs,
            String rowVar, Type.RelationType rowType, Set<String> stripped,
            StoreResolver.Context context) {
        var navSteps = Pipelines.outerNavSteps(cs.pipeline());
        boolean[] rewrote = {false};
        TypedSpec out = rewriteNavReads(n, cs, rowVar, rowType, stripped,
                navSteps, context, rewrote);
        if (!rewrote[0]) {
            return null;
        }
        // MIXED leaf (agg + plain column reads): the direct emission
        // bypasses the row-read rewrite, so residual parent-row reads
        // would dangle — keep the loud wall for that shape until demanded
        Set<String> residual = new LinkedHashSet<>();
        Pipelines.collectVarReads(out, cs.rowVar(), residual);
        return residual.isEmpty() ? out : null;
    }

    private TypedSpec rewriteNavReads(TypedSpec n, ClassSource cs,
            String rowVar, Type.RelationType rowType, Set<String> stripped,
            Map<String, TypedNavigate> navSteps,
            StoreResolver.Context context, boolean[] rewrote) {
        if (n instanceof TypedPropertyAccess pa
                && pa.source() instanceof TypedVariable v
                && v.name().equals(cs.rowVar())
                && stripped.contains(pa.property())
                && navSteps.get(pa.property()) != null) {
            TypedNavigate st = navSteps.get(pa.property());
            if (!(st.target() instanceof TypedGetAll ng)
                    || st.predicate().parameters().size() != 2
                    || temporal.temporalStrategy(ng.classFqn()) != null) {
                return n;
            }
            ClassSource t = sources.get(
                    dispatch.apply(context, ng.classFqn()), ng.classFqn());
            TypedSpec tPipe = Pipelines.materialize(t.pipeline(),
                    Set.of(), t.classFqn()).pipeline();
            Type.RelationType tRow = (Type.RelationType) tPipe.info().type();
            String pVar = st.predicate().parameters().get(0);
            String tVar = st.predicate().parameters().get(1);
            var one = com.legend.compiler.element.type.Multiplicity.Bounded.ONE;
            List<TypedSpec> corrBody = st.predicate().body().stream().map(x ->
                    Pipelines.rewriteRowReads(x, pVar, Map.of(), Set.of(),
                            v2 -> new TypedVariable(rowVar,
                                    new ExprType(rowType, one)))).toList();
            TypedLambda corr = new TypedLambda(List.of(tVar), corrBody,
                    new ExprType(new Type.FunctionType(
                            List.of(new Type.Param(tRow, one)),
                            new Type.Param(Type.Primitive.BOOLEAN, one)), one));
            rewrote[0] = true;
            return new TypedFilter(tPipe, corr, new ExprType(tRow, one));
        }
        if (n instanceof TypedNativeCall c) {
            List<TypedSpec> args = c.args().stream().map(a ->
                    rewriteNavReads(a, cs, rowVar, rowType, stripped,
                            navSteps, context, rewrote)).toList();
            return new TypedNativeCall(c.callee(), args, c.info());
        }
        return n;
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
        return primitiveArrayChild(property, targetPipeline, cond, colRead,
                parentRowVar, parentRowType,
                java.util.function.UnaryOperator.identity());
    }

    /** {@code valueWrap} rebuilds any per-element wrapper calls
     * (toString/datePart chains) over the correlated child-row read. */
    private TypedSerializeGraph.Child primitiveArrayChild(String property,
            TypedSpec targetPipeline, TypedLambda cond,
            TypedPropertyAccess colRead,
            String parentRowVar, Type.RelationType parentRowType,
            java.util.function.UnaryOperator<TypedSpec> valueWrap) {
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
        TypedSpec value = valueWrap.apply(new TypedPropertyAccess(
                new TypedVariable(childVar,
                        new ExprType(targetRow,
                                com.legend.compiler.element.type
                                        .Multiplicity.Bounded.ONE)),
                colRead.property(), colRead.info()));
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
            Type.RelationType parentRowType, TypedSpec parentPipeline) {
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
            TypedNewInstanceCast srcCast = null;
            // Unwrap the M2M cast (^Target($src.assocProp)) and toOne.
            if (inner instanceof TypedNewInstanceCast nic) {
                srcCast = nic;
                inner = nic.source();
            }
            if (inner instanceof TypedNativeCall c1
                    && c1.args().size() == 1
                    && c1.callee().qualifiedName().equals("meta::pure::functions::multiplicity::toOne")) {
                inner = c1.args().get(0);
            }
            if (inner instanceof TypedNewInstanceCast nic2) {
                srcCast = nic2;
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
            // WHOLE-SOURCE marker (trader[trader_set]: $src): the bare
            // composed-row var, source-class-typed — the child is the SAME
            // row seen through the cast's set; inline, no join (XStore
            // route A, whole-$src edit).
            if (inner instanceof TypedVariable wv
                    && wv.name().equals(cs.rowVar())
                    && wv.info().type() instanceof Type.ClassType wSrc
                    && srcCast != null) {
                return wholeSrcChild(cs, node, srcCast, wSrc.fqn(), context,
                        parentPipeline);
            }
            // A NAVIGATE-SLOT read ($row.<alias>, the relational
            // association injected into the source pipeline): the slot's
            // TypedNavigate carries the raw target and the join predicate.
            if (inner instanceof TypedPropertyAccess pa2
                    && pa2.source() instanceof TypedVariable v2
                    && v2.name().equals(cs.rowVar())) {
                var navSteps = Pipelines.outerNavSteps(cs.pipeline());
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
                // GRAPHFETCH ALWAYS TAKES THE FK FALLBACK (V1 §D.5; corpus
                // testOtherwiseEmbeddedMapping: description serializes the
                // fallback table's NOT_SO_GOOD_DETAIL 'P 1', NOT the
                // partial's parent column 'Bond 1') — the whole child
                // routes through the fallback's navigate; the partial
                // serves QUERY-position reads only.
                TypedSpec fb = ow2.args().get(1);
                while (fb instanceof TypedNativeCall fw && fw.args().size() == 1
                        && (fw.callee().qualifiedName().equals(
                                "meta::pure::functions::multiplicity::toOne")
                            || fw.callee().qualifiedName().equals(
                                "meta::pure::functions::collection::first"))) {
                    fb = fw.args().get(0);
                }
                if (fb instanceof TypedPropertyAccess fpa
                        && fpa.source() instanceof TypedVariable fv
                        && fv.name().equals(cs.rowVar())) {
                    var owNav = Pipelines.outerNavSteps(cs.pipeline())
                            .get(fpa.property());
                    if (owNav != null) {
                        return navSlotChild(cs, node, owNav, null,
                                context, parentRowVar, parentRowType);
                    }
                }
                emb = ow2.args().get(0);
            }
            if (emb instanceof TypedNewInstance ctor2) {
                return embeddedChild(cs, node, ctor2, context, parentPipeline,
                        ow2 == null ? null : ow2.args().get(1));
            }
            throw new NotImplementedException("graph child '" + node.property()
                    + "' of class '" + cs.classFqn() + "' is mapped as an"
                    + " embedded/join-slot/otherwise/M2M binding — only"
                    + " association children are supported yet (H4b/H5c)");
        }
        // MIXED-UNION extent: class-typed children dispatch PER MEMBER ARM —
        // ONE correlated subquery over the keyed child union with the
        // OR-of-member-keys condition (route b, XSTORE_LEG design)
        if ("union".equals(cs.setId())) {
            TypedSerializeGraph.Child mcc = mixedUnionChild(cs, node, context,
                    parentRowVar, parentRowType);
            if (mcc != null) {
                return mcc;
            }
        }
        // the child tree's LEAF property names ride as demanded leaves —
        // a leaf mapped through the target's own join slots needs them
        // CONVERTED, not stripped (H4b; same rule as leafSlotDemand)
        java.util.Set<String> childLeaves = new java.util.LinkedHashSet<>();
        for (TypedGraphTree c0 : node.children()) {
            childLeaves.add(c0.property());
        }
        AssociationJoins.AssocJoin aj = assocMaterial.associationJoin(temporal,
                cs, node.property(), context, /*forExists*/ true, childLeaves);
        var assoc = ctx.findAssociationOf(cs.classFqn(), node.property()).orElseThrow();
        var end = assoc.property1().propertyName().equals(node.property())
                ? assoc.property1() : assoc.property2();
        boolean toMany = !end.isToOne();
        return correlatedGraphChild(aj.target(), aj.targetPipeline(), aj.targetRow(),
                aj.condition(), toMany, node, parentRowVar, parentRowType,
                context, aj.targetSlotPrefixes());
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
        String setHint = ctx.routedTargetSetOf(cs.mappingFqn(),
                node.property()).orElse(null);
        ClassSource child = childClass.equals(rawTarget)
                ? sources.get(dispatch.apply(context, rawTarget), rawTarget,
                        setHint, target -> dispatch.apply(context, target), key)
                : sources.get(cs.mappingFqn(), childClass, setHint,
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
        // demand CLOSES over join-condition prerequisites: a chained
        // composite slot (A__B) rides its base slot (A) — stripping the
        // base breaks the composite (same rule as the flatten route)
        Pipelines.Materialized cMat = Pipelines.materialize(
                child.pipeline(),
                Pipelines.closeOverConditions(child.pipeline(),
                        leafSlotDemand(child, node)),
                childClass);
        // MILESTONED SLOTS inside the child: the hop's date context
        // windows the child's own slot-join targets (mirror of the
        // relational navigate's slot-target stamping) — unstamped they
        // serialize every version row (the classification fan)
        TypedSpec cPipe = cMat.pipeline();
        TemporalContext hopCtx = temporal.contextAt(node.property(),
                childClass, TemporalContext.NONE);
        if (!hopCtx.isEmpty()
                && temporal.hasMilestonedSlotTarget(child.pipeline())) {
            cPipe = temporal.filterMilestonedJoinTargets(cPipe, hopCtx);
        }
        // MILESTONED child: the tree node's date arg registered as the
        // hop's temporal spec (collectTreeSweeps) — the child pipeline
        // filters by its window here, exactly like the relational
        // navigate's target (unfiltered children serialize every version
        // row: the multi-level union 2->4 duplication)
        TypedSpec childPipe = temporal.temporalTargetPipe(cs, child,
                node.property(), cPipe);
        // GRAPH children pair STRICTLY per union member (the engine's
        // graph executor runs per-member serial child queries) — a merged
        // union navigate carries the paired variant for exactly this
        // consumer (TypedNavigate.pairedPredicate)
        return correlatedGraphChild(child, childPipe,
                (Type.RelationType)
                        childPipe.info().type(),
                nav.pairedPredicate().orElse(nav.predicate()),
                toMany, node, parentRowVar, parentRowType, context,
                cMat.slotPrefixes());
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
                child.pipeline(), leafSlotDemand(child, node), childCls.fqn());
        return correlatedGraphChild(child, cMat.pipeline(),
                (Type.RelationType)
                        cMat.pipeline().info().type(),
                aj.condition(), toMany, node, parentRowVar, parentRowType,
                context, cMat.slotPrefixes());
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
        return correlatedGraphChild(target, targetPipeline, targetRow,
                condition, toMany, node, parentRowVar, parentRowType,
                context, Map.of());
    }

    /** {@code slotPrefixes}: the child pipeline's CONVERTED slot aliases
     * (from its materialization) — leaves reading through them rewrite to
     * the prefixed flat columns instead of walling as stripped (H4b:
     * ViewAtChild's pnl reads Order's own @OrderPnlView join slot, which
     * leafSlotDemand had the materialization convert). */
    TypedSerializeGraph.Child correlatedGraphChild(ClassSource target,
            TypedSpec targetPipeline,
            Type.RelationType targetRow,
            TypedLambda condition, boolean toMany, TypedGraphTree node,
            String parentRowVar,
            Type.RelationType parentRowType,
            StoreResolver.Context context,
            Map<String, String> slotPrefixes) {
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
        // ONE CONTEXT PER CURSOR (engine getMilestoningContextFor
        // QualifiedProperty): a DATED hop's spec becomes the CHILD scope's
        // ROOT context — its leaves and filters substitute businessDate/
        // processingDate against the HOP date, not the (possibly absent)
        // outer root's ('filter predicate references businessDate' with a
        // non-temporal root and a dated tree node).
        TemporalFrame childFrame = temporal.nestedFrame(target.classFqn(),
                node.property());
        GraphEmission em = childFrame == null ? this
                : new GraphEmission(ctx, sources, assocMaterial, childFrame,
                        dispatch, freshVar);
        Set<String> childStripped = new LinkedHashSet<>(
                Pipelines.slotAliases(target.pipeline()));
        childStripped.removeAll(slotPrefixes.keySet());
        TypedSerializeGraph child = em.buildGraphNode(target, childRel,
                slotPrefixes, childStripped, childVar,
                node.children(), context, toMany, childInfo);
        return new TypedSerializeGraph.Child(
                childKey(node, target.classFqn()), child);
    }

    /** The serialized key for a CLASS child: a QUALIFIED spelling with
     * empty parens over a milestoned target spells the RESOLVED context
     * date — the engine serializes the source call spelling with the
     * propagated date filled in
     * ({@code synonyms(2023-10-15T00:00:00+0000)}). */
    private String childKey(TypedGraphTree node, String childFqn) {
        if (node.alias() == null && node.qualified()
                && node.args().isEmpty()) {
            MilestoningStrategy strat = temporal.temporalStrategy(childFqn);
            TypedSpec d = strat == null ? null
                    : temporal.rootContextDate(
                            strat != MilestoningStrategy.PROCESSING);
            if (d instanceof com.legend.compiler.spec.typed.TypedCDate cd) {
                String iso = cd.value().toEngineString();
                return node.property() + "(" + iso
                        + (iso.indexOf('T') >= 0 ? "+0000" : "") + ")";
            }
        }
        return keyOf(node);
    }

    /** An EMBEDDED graph child: leaves come straight from the ^Inner
     * ctor (parent-row expressions), nested embedded ctors recurse as
     * further inline children. Non-ctor leaves (slot/assoc reads inside
     * the embedded) stay loud — their join machinery is a later rung. */
    /**
     * A class-typed child over a MIXED-UNION extent: per-member dispatch as
     * ONE correlated subquery — target = the keyed child union (arms paired
     * by each parent member's declared route target set), condition =
     * OR over pairs of AND over key-column equalities (NULL keys in other
     * arms make cross-pair matches impossible). Null when the property is
     * not class-typed (scalar walls stay downstream).
     */
    private TypedSerializeGraph.Child mixedUnionChild(ClassSource cs,
            TypedGraphTree node, StoreResolver.Context context,
            String parentRowVar, Type.RelationType parentRowType) {
        String childClass = mixedChildClassOf(cs.classFqn(), node.property());
        if (childClass == null) {
            return null;
        }
        ClassSources.MixedChild mc = sources.mixedChildMaterial(
                cs.mappingFqn(), cs.classFqn(), node.property(), childClass);
        if (mc == null) {
            return null;
        }
        var one = com.legend.compiler.element.type.Multiplicity.Bounded.ONE;
        var eqFns = ctx.findFunction("meta::pure::functions::boolean::equal")
                .stream().filter(f -> f.parameters().size() == 2).toList();
        var andFns = ctx.findFunction("meta::pure::functions::boolean::and")
                .stream().filter(f -> f.parameters().size() == 2).toList();
        var orFns = ctx.findFunction("meta::pure::functions::boolean::or")
                .stream().filter(f -> f.parameters().size() == 2).toList();
        if (eqFns.size() != 1 || andFns.size() != 1 || orFns.size() != 1) {
            throw new IllegalStateException("resolver bug: expected one 2-arg"
                    + " equal/and/or in the catalog");
        }
        ExprType boolInfo = new ExprType(Type.Primitive.BOOLEAN, one);
        String pv = "u_pr";
        String tv = "u_tr";
        Type.RelationType tRow = mc.target().rowType();
        ExprType pInfo = new ExprType(parentRowType, one);
        ExprType tInfo = new ExprType(tRow, one);
        TypedSpec cond = null;
        for (java.util.List<String> pair : mc.keysPerPair()) {
            TypedSpec pairCond = null;
            for (String keyName : pair) {
                Type.Column kc = mixedKeyColumn(tRow, keyName);
                mixedKeyColumn(parentRowType, keyName);   // parent carries it
                ExprType ki = new ExprType(kc.type(), kc.multiplicity());
                TypedSpec eq = new TypedNativeCall(eqFns.get(0), List.of(
                        new TypedPropertyAccess(new TypedVariable(pv, pInfo),
                                keyName, ki),
                        new TypedPropertyAccess(new TypedVariable(tv, tInfo),
                                keyName, ki)), boolInfo);
                pairCond = pairCond == null ? eq : new TypedNativeCall(
                        andFns.get(0), List.of(pairCond, eq), boolInfo);
            }
            cond = cond == null ? pairCond : new TypedNativeCall(
                    orFns.get(0), List.of(cond, pairCond), boolInfo);
        }
        var fnT = new Type.FunctionType(
                List.of(new Type.Param(parentRowType, one),
                        new Type.Param(tRow, one)),
                new Type.Param(Type.Primitive.BOOLEAN, one));
        TypedLambda condL = new TypedLambda(List.of(pv, tv), List.of(cond),
                new ExprType(fnT, one));
        return correlatedGraphChild(mc.target(), mc.target().pipeline(), tRow,
                condL, mixedChildToMany(cs.classFqn(), node.property()), node,
                parentRowVar, parentRowType, context);
    }

    private static Type.Column mixedKeyColumn(Type.RelationType row,
            String name) {
        for (Type.Column c : row.columns()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        throw new IllegalStateException("resolver bug: mixed-union key"
                + " column '" + name + "' is not on the row — the extent"
                + " and the child material drifted");
    }

    private String mixedChildClassOf(String clsFqn, String prop) {
        var p = ctx.findProperty(clsFqn, prop).orElse(null);
        if (p != null && p.type() instanceof Type.ClassType ct) {
            return ct.fqn();
        }
        return ctx.findAssociationOf(clsFqn, prop)
                .map(a -> (a.property1().propertyName().equals(prop)
                        ? a.property1() : a.property2()).targetClassFqn())
                .orElse(null);
    }

    private boolean mixedChildToMany(String clsFqn, String prop) {
        var p = ctx.findProperty(clsFqn, prop).orElse(null);
        if (p != null && p.type() instanceof Type.ClassType) {
            return !(p.multiplicity() instanceof com.legend.compiler.element
                    .type.Multiplicity.Bounded mb && mb.isToOne());
        }
        return ctx.findAssociationOf(clsFqn, prop)
                .map(a -> !(a.property1().propertyName().equals(prop)
                        ? a.property1() : a.property2()).isToOne())
                .orElse(true);
    }

    /**
     * WHOLE-SOURCE child ({@code trader[trader_set]: $src}): the child
     * instance is the SAME source row seen through ANOTHER set of this
     * mapping — resolve that set (by the cast's declared set id), guard
     * that it composes over the parent's row columns, and rebase its
     * bindings onto the parent row var. Inline, no join — the engine's
     * same-instance ModelStore composition (XStore leg, route A).
     */
    private TypedSerializeGraph.Child wholeSrcChild(ClassSource cs,
            TypedGraphTree node, TypedNewInstanceCast cast,
            String srcClassFqn, StoreResolver.Context context,
            TypedSpec parentPipeline) {
        ClassSource child = cast.targetSetId() != null
                ? sources.get(cs.mappingFqn(), cast.classFqn(),
                        cast.targetSetId(), null, "")
                : sources.get(cs.mappingFqn(), cast.classFqn());
        Type.RelationType rowT = (Type.RelationType)
                parentPipeline.info().type();
        // audit 24 F4: SOURCE-CLASS identity is the real same-frame check —
        // two frames can share every column NAME (S_Trade vs S_Trade2) and
        // a name-subset proxy alone would silently serve the parent's rows
        // as the other class's data. The structural subset check remains
        // as the fallback ONLY when the child's source class is unknown.
        if (child.sourceClass() != null
                && !child.sourceClass().equals(srcClassFqn)) {
            throw new NotImplementedException("whole-source graph child '"
                    + node.property() + "' of '" + cs.classFqn()
                    + "': set '" + cast.classFqn() + "' composes over source"
                    + " class '" + child.sourceClass() + "' but the marker's"
                    + " row is a '" + srcClassFqn + "' frame — different"
                    + " sources never share a row");
        }
        Set<String> parentCols = new LinkedHashSet<>();
        for (Type.Column pc : rowT.columns()) {
            parentCols.add(pc.name());
        }
        for (Type.Column cc : child.rowType().columns()) {
            if (!parentCols.contains(cc.name())) {
                throw new NotImplementedException("whole-source graph child '"
                        + node.property() + "' of '" + cs.classFqn()
                        + "': set '" + cast.classFqn() + "' composes over"
                        + " column '" + cc.name() + "' absent from the"
                        + " parent row — the sets do not share the source"
                        + " frame (source class '" + srcClassFqn + "')");
            }
        }
        var rowInfo = new ExprType(rowT,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        List<TypedFuncCol> leaves = new ArrayList<>();
        List<TypedSerializeGraph.Child> nested = new ArrayList<>();
        for (TypedGraphTree c : node.children()) {
            TypedSpec e = child.bindings().get(c.property());
            // Non-scalar entries — association ends (XStore included),
            // class-typed bindings, nested whole-source hops — recurse the
            // ORDINARY graph-child machinery rooted at the child set: the
            // row is frame-compatible (guarded above), so the child set IS
            // this row's class source and every dispatch arm applies.
            if (e == null && c.children().isEmpty()) {
                throw new MappingResolutionException("property '"
                        + c.property() + "' of whole-source child '"
                        + node.property() + "' on class '" + cast.classFqn()
                        + "' is not mapped in mapping '" + cs.mappingFqn()
                        + "'", cast.classFqn());
            }
            if (e == null || e.info().type() instanceof Type.ClassType) {
                nested.add(graphChild(child, c, context, cs.rowVar(), rowT,
                        parentPipeline));
                continue;
            }
            TypedSpec e2 = renameRowVar(e, child.rowVar(), cs.rowVar(),
                    rowInfo);
            var lFn = new Type.FunctionType(
                    List.of(new Type.Param(rowT,
                            com.legend.compiler.element.type.Multiplicity
                                    .Bounded.ONE)),
                    new Type.Param(e2.info().type(), e2.info().multiplicity()));
            leaves.add(new TypedFuncCol(keyOf(c),
                    new TypedLambda(List.of(cs.rowVar()), List.of(e2),
                            new ExprType(lFn,
                                    com.legend.compiler.element.type
                                            .Multiplicity.Bounded.ONE))));
        }
        TypedSerializeGraph nodeG = new TypedSerializeGraph(parentPipeline,
                cs.rowVar(), leaves, nested, false, false, cast.classFqn(),
                rowInfo, true);
        return new TypedSerializeGraph.Child(
                childKey(node, cast.classFqn()), nodeG);
    }

    /** {@code from}-var reads re-pointed at {@code to} with the parent's
     * row info — same-frame rebase for whole-source children. */
    private TypedSpec renameRowVar(TypedSpec n, String from, String to,
            ExprType rowInfo) {
        if (n instanceof TypedVariable v && v.name().equals(from)) {
            return new TypedVariable(to, rowInfo);
        }
        return SyntheticHeads.rebuildChildren(n,
                c -> renameRowVar(c, from, to, rowInfo));
    }

    private TypedSerializeGraph.Child embeddedChild(ClassSource cs,
            TypedGraphTree node, TypedNewInstance ctor,
            StoreResolver.Context context, TypedSpec parentPipeline) {
        return embeddedChild(cs, node, ctor, context, parentPipeline, null);
    }

    private TypedSerializeGraph.Child embeddedChild(ClassSource cs,
            TypedGraphTree node, TypedNewInstance ctor,
            StoreResolver.Context context, TypedSpec parentPipeline,
            TypedSpec otherwiseFallback) {
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
                parentPipeline.info().type();
        var rowInfo = new ExprType(rowT,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        List<TypedFuncCol> leaves = new ArrayList<>();
        List<TypedSerializeGraph.Child> nested = new ArrayList<>();
        for (TypedGraphTree c : want) {
            TypedSpec e = ctor.properties().get(c.property());
            if (e == null && otherwiseFallback != null) {
                // OTHERWISE per-leaf dispatch (map §6 / V1 §D.5): a leaf
                // MISSING from the embedded partial reads through the
                // FALLBACK join target — a correlated scalar subquery,
                // never a silent null
                HeadRel hr = navHeadRelation(
                        new SubqueryEnv(cs, context, cs.rowVar(), rowT),
                        otherwiseFallback, cs.rowVar());
                TypedSpec fb = hr == null ? null
                        : hr.target().bindings().get(c.property());
                if (fb != null
                        && !(fb.info().type() instanceof Type.ClassType)) {
                    e = scalarLeafSubquery(hr.rel(), hr.target().rowVar(),
                            hr.targetRow(), c.property(), fb);
                }
            }
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
                        + "' [otherwise fallback="
                        + (otherwiseFallback == null ? "ABSENT"
                                : otherwiseFallback.getClass().getSimpleName())
                        + "]", cs.classFqn());
            }
            TypedSpec ei = e;
            if (ei instanceof TypedNativeCall tc1 && tc1.args().size() == 1
                    && tc1.callee().qualifiedName().equals(
                            "meta::pure::functions::multiplicity::toOne")) {
                ei = tc1.args().get(0);
            }
            if (ei instanceof TypedNewInstance subCtor) {
                nested.add(embeddedChild(cs, c, subCtor, context,
                        parentPipeline));
                continue;
            }
            if (ei.info().type() instanceof Type.ClassType) {
                throw new NotImplementedException("embedded graph child '"
                        + node.property() + "." + c.property()
                        + "' is class-typed through a non-ctor binding —"
                        + " not supported yet");
            }
            // a TO-MANY PRIMITIVE leaf reading a join slot (the Inline
            // splice's authorId: @Book_Authorship | ... over String[*]):
            // the inline read would take ONE joined row — the engine
            // serializes the AGGREGATED array; same correlated emission
            // as the top-level to-many slot-read arm
            // single-arg WRAPPER CALLS peel for detection and REBUILD
            // over the correlated child row (authorId:
            // toString(@Book_Authorship | ...) — per-element scalar fns)
            TypedSpec peeled2 = ei;
            java.util.List<TypedNativeCall> wraps2 = new ArrayList<>();
            while (peeled2 instanceof TypedNativeCall wc2
                    && wc2.args().size() == 1) {
                wraps2.add(wc2);
                peeled2 = wc2.args().get(0);
            }
            // the guard is the DECLARED property multiplicity (the
            // Inline-splice read types per COLUMN — [0..1] — masking the
            // property's [*])
            var declProp2 = ctx.findProperty(childClass, c.property())
                    .orElse(null);
            boolean many2 = declProp2 != null && !(declProp2.multiplicity()
                    instanceof com.legend.compiler.element.type
                            .Multiplicity.Bounded dm2
                    && Integer.valueOf(1).equals(dm2.upper()));
            if (many2 && peeled2 instanceof TypedPropertyAccess colPa2
                    && colPa2.source() instanceof TypedPropertyAccess slotPa2
                    && slotPa2.source() instanceof TypedVariable sv2
                    && sv2.name().equals(cs.rowVar())) {
                java.util.function.UnaryOperator<TypedSpec> rewrap =
                        v0 -> {
                            TypedSpec out2 = v0;
                            for (int wi = wraps2.size() - 1; wi >= 0; wi--) {
                                TypedNativeCall w3 = wraps2.get(wi);
                                out2 = new TypedNativeCall(w3.callee(),
                                        List.of(out2), w3.info());
                            }
                            return out2;
                        };
                // the hop's date context WINDOWS the correlated target
                // (authors($businessDate): unstamped it collects every
                // version row — the 5002 leak)
                TemporalContext hc3 = temporal.contextAt(node.property(),
                        childClass, TemporalContext.NONE);
                java.util.function.UnaryOperator<TypedSpec> window3 =
                        t3 -> !hc3.isEmpty()
                                && temporal.temporalStrategy(childClass)
                                        != null
                                ? temporal.stampForClassOrDefer(t3, hc3,
                                        childClass, node.property())
                                : t3;
                var navE = Pipelines.outerNavSteps(cs.pipeline())
                        .get(slotPa2.property());
                if (navE != null) {
                    nested.add(primitiveArrayChild(keyOf(c),
                            window3.apply(navE.target()), navE.predicate(),
                            colPa2, cs.rowVar(), rowT, rewrap));
                    continue;
                }
                var slotE = Pipelines.joinSlots(cs.pipeline())
                        .get(slotPa2.property());
                if (slotE != null) {
                    nested.add(primitiveArrayChild(keyOf(c),
                            window3.apply(slotE.target()), slotE.condition(),
                            colPa2, cs.rowVar(), rowT, rewrap));
                    continue;
                }
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
        // INLINE child: the pipeline is a row-typing CARRIER only (the
        // envelope reads leaves off the PARENT base) — carry the parent's
        // MATERIALIZED pipeline, never the raw cs.pipeline() whose
        // undemanded navigate thunks still hold getAll (the embedded
        // family's join-inside-embedded shape tripped the escapee wall)
        // a TO-MANY embedded property serializes as an ARRAY around the
        // inline object (engine: "authors":[{...}])
        boolean many = !(prop.multiplicity()
                instanceof com.legend.compiler.element.type.Multiplicity
                        .Bounded pb
                && Integer.valueOf(1).equals(pb.upper()));
        TypedSerializeGraph nodeG = new TypedSerializeGraph(parentPipeline,
                cs.rowVar(), leaves, nested, many, false, childClass,
                rowInfo, true);
        return new TypedSerializeGraph.Child(
                childKey(node, childClass), nodeG);
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
        return derivedLeaf(cs.bindings(), cs.classFqn(), node,
                new SubqueryEnv(cs, context, rowVar, rowType));
    }

    /** The enclosing-frame material the inline route needs to fall back
     * to a CORRELATED SCALAR SUBQUERY for a navigation SUB-EXPRESSION
     * (map §7 contract 4: qualified properties are child computations).
     * Null on paths without a frame (embedded ctor leaves). */
    record SubqueryEnv(ClassSource cs, StoreResolver.Context context,
            String rowVar, Type.RelationType rowType) {
    }

    /** The corr-filtered TARGET RELATION for a navigation HEAD read off
     * {@code $this} — the shared spine of the qualifier-as-child-
     * computation emissions (scalar leaf subquery, emptiness). Null when
     * the head shape does not match. */
    private record HeadRel(ClassSource target, Type.RelationType targetRow,
            TypedSpec rel) {
    }

    private HeadRel navHeadRelation(SubqueryEnv env, TypedSpec hop,
            String thisVar) {
        ClassSource cs = env.cs();
        StoreResolver.Context context = env.context();
        while (hop instanceof TypedNativeCall w && w.args().size() == 1
                && (w.callee().qualifiedName().equals(
                        "meta::pure::functions::multiplicity::toOne")
                    || w.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::first"))) {
            hop = w.args().get(0);
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
            // (the same channel query-position property functions use).
            // Generated-date reads ($this.businessDate from the inlined
            // qualifier) NORMALIZE against the fetch's root context — the
            // literal date the local subquery can actually spell (the
            // cycle-71 nested-cursor rule; a dateless root keeps the raw
            // read and its honest lowering wall).
            headProp = hma.property();
            hopDates = temporal.normalizeContextDates(hma.dates());
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
            if (bindingRead == null) {
                // the OTHERWISE-fallback case: the hop IS the slot read
                // (an alias, not a class property — bindings has no entry)
                bindingRead = hop;
            }
            // an OTHERWISE-wrapped binding routes through its FALLBACK
            // read (the FK-join navigate; the embedded partial serves
            // only its own ctor leaves)
            var owN = com.legend.resolver.Substitution
                    .otherwiseOf(bindingRead);
            if (owN != null) {
                bindingRead = owN.args().get(1);
            }
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
                nav = Pipelines.outerNavSteps(cs.pipeline()).get(bpa.property());
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
            cond = nav.pairedPredicate().orElse(nav.predicate());
        }
        String pVar = cond.parameters().get(0);
        String tVar = cond.parameters().get(1);
        List<TypedSpec> corrBody = cond.body().stream().map(cb ->
                Pipelines.rewriteRowReads(cb, pVar, Map.of(), Set.of(),
                        v -> new TypedVariable(env.rowVar(),
                                new ExprType(env.rowType(),
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
        return new HeadRel(target, targetRow,
                new TypedFilter(targetPipeline, corr, targetPipeline.info()));
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
        SubqueryEnv env = new SubqueryEnv(cs, context,
                parentRowVar, parentRowType);
        HeadRel hr = navHeadRelation(env, hop, thisVar);
        if (hr == null) {
            // DEPTH-2+ chain ($this.classification(bd).exchange(d).name):
            // resolve the INNER chain to its frame, then the remaining
            // hop+leaf recurse AS a nested correlated subquery projected
            // over the inner frame's row (the engine chains the joins;
            // nesting keeps each hop's window on its own alias)
            TypedSpec innerSrc = switch (hop) {
                case TypedPropertyAccess hp -> hp.source();
                case com.legend.compiler.spec.typed.TypedMilestonedAccess hm ->
                        hm.source();
                default -> null;
            };
            HeadRel ih = innerSrc == null ? null
                    : navHeadRelation(env, innerSrc, thisVar);
            if (ih == null) {
                return null;
            }
            String iVar = ih.target().rowVar();
            TypedVariable iRow = new TypedVariable(iVar,
                    new ExprType(ih.targetRow(),
                            com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ONE));
            TypedSpec rebuiltHop = switch (hop) {
                case TypedPropertyAccess hp -> new TypedPropertyAccess(
                        iRow, hp.property(), hp.info());
                case com.legend.compiler.spec.typed.TypedMilestonedAccess hm ->
                        new com.legend.compiler.spec.typed.TypedMilestonedAccess(
                                iRow, hm.property(), hm.dates(), hm.sweep(),
                                hm.info());
                default -> null;
            };
            TypedSpec nested = navLeafSubquery(ih.target(),
                    new TypedPropertyAccess(rebuiltHop, leaf.property(),
                            leaf.info()),
                    iVar, context, iVar, ih.targetRow());
            if (nested == null) {
                return null;
            }
            var nFn = new Type.FunctionType(
                    List.of(new Type.Param(ih.targetRow(),
                            com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ONE)),
                    new Type.Param(leaf.info().type(),
                            com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ZERO_ONE));
            Type.RelationType nCol = new Type.RelationType(List.of(
                    new Type.Column(leaf.property(), leaf.info().type(),
                            com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ZERO_ONE)));
            TypedSpec nProj = new com.legend.compiler.spec.typed.TypedProject(
                    ih.rel(),
                    List.of(new TypedFuncCol(leaf.property(),
                            new TypedLambda(List.of(iVar), List.of(nested),
                                    new ExprType(nFn,
                                            com.legend.compiler.element.type
                                                    .Multiplicity.Bounded.ONE)))),
                    new ExprType(nCol,
                            com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ZERO_MANY));
            return new com.legend.compiler.spec.typed.TypedLimit(nProj,
                    new com.legend.compiler.spec.typed.TypedCInteger(1L,
                            ExprType.one(Type.Primitive.INTEGER)),
                    new ExprType(nCol,
                            com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ZERO_ONE));
        }
        ClassSource target = hr.target();
        Type.RelationType targetRow = hr.targetRow();
        TypedSpec leafBind = target.bindings().get(leaf.property());
        if (leafBind == null
                || leafBind.info().type() instanceof Type.ClassType) {
            return null;
        }
        TypedSpec rel = hr.rel();
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
            var predFn = new Type.FunctionType(
                    List.of(new Type.Param(targetRow,
                            com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ONE)),
                    new Type.Param(Type.Primitive.BOOLEAN,
                            com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ONE));
            rel = new TypedFilter(rel,
                    new TypedLambda(List.of(target.rowVar()),
                            List.of(pb), new ExprType(predFn,
                                    com.legend.compiler.element.type
                                            .Multiplicity.Bounded.ONE)),
                    rel.info());
        }
        return scalarLeafSubquery(rel, target.rowVar(), targetRow,
                leaf.property(), leafBind);
    }

    /** The [0..1] scalar-subquery tail shared by the nav-leaf and
     * otherwise-fallback emissions: project ONE leaf column over the
     * corr-filtered relation, LIMIT 1 (pure toOne semantics — a plain
     * scalar subquery, never a LIST aggregation). */
    static TypedSpec scalarLeafSubquery(TypedSpec rel, String rowVar,
            Type.RelationType targetRow, String leafName, TypedSpec leafBind) {
        var leafFn = new Type.FunctionType(
                List.of(new Type.Param(targetRow,
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                new Type.Param(leafBind.info().type(),
                        leafBind.info().multiplicity()));
        Type.RelationType oneCol = new Type.RelationType(List.of(
                new Type.Column(leafName, leafBind.info().type(),
                        leafBind.info().multiplicity())));
        TypedSpec proj = new com.legend.compiler.spec.typed.TypedProject(rel,
                List.of(new TypedFuncCol(leafName,
                        new TypedLambda(List.of(rowVar),
                                List.of(leafBind),
                                new ExprType(leafFn,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE)))),
                new ExprType(oneCol,
                        com.legend.compiler.element.type
                                .Multiplicity.Bounded.ZERO_MANY));
        return new com.legend.compiler.spec.typed.TypedLimit(proj,
                new com.legend.compiler.spec.typed.TypedCInteger(1L,
                        ExprType.one(Type.Primitive.INTEGER)),
                new ExprType(oneCol,
                        com.legend.compiler.element.type
                                .Multiplicity.Bounded.ZERO_ONE));
    }

    private TypedSpec derivedLeaf(Map<String, TypedSpec> bindings,
            String classFqn, TypedGraphTree node) {
        return derivedLeaf(bindings, classFqn, node, null);
    }

    private TypedSpec derivedLeaf(Map<String, TypedSpec> bindings,
            String classFqn, TypedGraphTree node, SubqueryEnv env) {
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
                classFqn, prop, env);
    }

    /** Substitute {@code $this.<stored>} reads with the row bindings.
     * Rebuild arms cover the expression shapes derived bodies produce;
     * an UNHANDLED node still CONTAINING {@code $this} throws loud —
     * never silent (an unreplaced var would only error later at the
     * lowering, far from its cause). */
    /** toOne/first wrappers drop for SHAPE matching (the value they wrap
     * is the read). */
    private static TypedSpec unwrapToOneFirst(TypedSpec v) {
        while (v instanceof TypedNativeCall w && w.args().size() == 1
                && (w.callee().qualifiedName().equals(
                        "meta::pure::functions::multiplicity::toOne")
                    || w.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::first"))) {
            v = w.args().get(0);
        }
        return v;
    }

    private TypedSpec inlineThis(TypedSpec n, String thisVar,
            Map<String, TypedSpec> binds, Map<String, TypedSpec> bindings,
            String classFqn, String prop) {
        return inlineThis(n, thisVar, binds, bindings, classFqn, prop, null);
    }

    private TypedSpec inlineThis(TypedSpec n, String thisVar,
            Map<String, TypedSpec> binds, Map<String, TypedSpec> bindings,
            String classFqn, String prop, SubqueryEnv env) {
        if (n instanceof TypedVariable bv && binds.containsKey(bv.name())) {
            return binds.get(bv.name());
        }
        // EMBEDDED mid-hop ($this.address.name where 'address' binds to
        // an embedded ctor): the leaf is the ctor's own sub-binding — a
        // parent-row read, no join (the engine's embedded per-leaf rule)
        TypedSpec eSrc = n instanceof TypedPropertyAccess epa0
                ? unwrapToOneFirst(epa0.source()) : null;
        if (n instanceof TypedPropertyAccess epa
                && eSrc instanceof TypedPropertyAccess mid
                && mid.source() instanceof TypedVariable emv
                && emv.name().equals(thisVar)) {
            TypedSpec mb = bindings.get(mid.property());
            while (mb instanceof TypedNativeCall mw && mw.args().size() == 1
                    && (mw.callee().qualifiedName().equals(
                            "meta::pure::functions::multiplicity::toOne")
                        || mw.callee().qualifiedName().equals(
                            "meta::pure::functions::collection::first"))) {
                mb = mw.args().get(0);
            }
            if (mb instanceof com.legend.compiler.spec.typed
                    .TypedNewInstance eni) {
                TypedSpec lb = eni.properties().get(epa.property());
                if (lb != null
                        && !(lb.info().type() instanceof Type.ClassType)) {
                    return lb;
                }
            }
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
        // EMPTINESS over a navigation head ($this.assoc->isEmpty()): the
        // corr-filtered target relation feeds the emptiness native — the
        // §133 lowering renders [NOT] EXISTS (engine qualifier goldens)
        if (env != null && n instanceof TypedNativeCall ec
                && ec.args().size() == 1
                && (ec.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::isEmpty")
                    || ec.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::isNotEmpty"))
                && containsVar(ec.args().get(0), thisVar)) {
            HeadRel hr = navHeadRelation(env, ec.args().get(0), thisVar);
            if (hr != null) {
                return new TypedNativeCall(ec.callee(),
                        List.of(hr.rel()), n.info());
            }
        }
        if (n instanceof TypedNativeCall c) {
            List<TypedSpec> args = new ArrayList<>(c.args().size());
            for (TypedSpec a : c.args()) {
                args.add(inlineThis(a, thisVar, binds, bindings, classFqn, prop, env));
            }
            return new TypedNativeCall(c.callee(), args, c.info());
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedIf i) {
            return new com.legend.compiler.spec.typed.TypedIf(
                    inlineThis(i.condition(), thisVar, binds, bindings, classFqn, prop, env),
                    inlineThis(i.thenBranch(), thisVar, binds, bindings, classFqn, prop, env),
                    i.elseBranch().map(e ->
                            inlineThis(e, thisVar, binds, bindings, classFqn, prop, env)),
                    n.info());
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedCollection tc) {
            List<TypedSpec> els = new ArrayList<>(tc.elements().size());
            for (TypedSpec e : tc.elements()) {
                els.add(inlineThis(e, thisVar, binds, bindings, classFqn, prop, env));
            }
            return new com.legend.compiler.spec.typed.TypedCollection(
                    els, tc.info());
        }
        if (containsVar(n, thisVar)) {
            // NAVIGATION SUB-EXPRESSION fallback: a nav+leaf chain inside
            // a larger expression emits as its own correlated scalar
            // subquery (map §7 contract 4 — the engine computes qualifier
            // expressions with navigations as embedded scalar subqueries).
            // TO-ONE reads only: the subquery LIMITs 1, so a to-many chain
            // (feeding an aggregate) would silently drop rows — stays loud.
            if (env != null
                    && n.info().multiplicity()
                            instanceof com.legend.compiler.element.type
                                    .Multiplicity.Bounded mb
                    && mb.upper() != null && mb.upper() <= 1) {
                TypedSpec sub = navLeafSubquery(env.cs(), n, thisVar,
                        env.context(), env.rowVar(), env.rowType());
                if (sub != null) {
                    return sub;
                }
            }
            throw new NotImplementedException("derived graph leaf '" + prop
                    + "' body node " + n.getClass().getSimpleName()
                    + " referencing $" + thisVar
                    + " is not inlinable yet");
        }
        return n;
    }

    /** Slot aliases the tree's LEAF bindings read off the child row —
     * the child pipeline's materialization demand (H4b: a leaf mapped
     * through the class's own join slots needs those slots CONVERTED,
     * not stripped; empty demand left them stripped and the leaf walled). */
    private static boolean isDateFamily(Type t) {
        return t == Type.Primitive.DATE || t == Type.Primitive.DATE_TIME
                || t == Type.Primitive.STRICT_DATE;
    }

    private static java.util.Set<String> leafSlotDemand(ClassSource child,
            TypedGraphTree node) {
        java.util.Set<String> universe =
                Pipelines.slotAliases(child.pipeline());
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (universe.isEmpty()) {
            return out;
        }
        for (TypedGraphTree c : node.children()) {
            TypedSpec b = child.bindings().get(c.property());
            if (b == null) {
                continue;
            }
            collectAliasReads(b, child.rowVar(), universe, out);
            // reads WRAPPED in computed expressions (concat over
            // $row.slot.COL) demand the slot the same way — any reference
            // shape converts, the direct pattern above is just the fast path
            for (String a : universe) {
                if (!out.contains(a)
                        && Pipelines.referencesAliasOn(b, child.rowVar(),
                                java.util.Set.of(a))) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    private static void collectAliasReads(TypedSpec n, String rowVar,
            java.util.Set<String> universe, java.util.Set<String> out) {
        if (n instanceof TypedPropertyAccess pa
                && pa.source() instanceof TypedPropertyAccess mid
                && mid.source() instanceof TypedVariable v
                && v.name().equals(rowVar)
                && universe.contains(mid.property())) {
            out.add(mid.property());
        }
        for (TypedSpec c : n.children()) {
            collectAliasReads(c, rowVar, universe, out);
        }
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
            } else if (a instanceof
                    com.legend.compiler.spec.typed.TypedVariable v) {
                // a VARIABLE arg keeps its source spelling — the engine
                // key is "classification($bd)" verbatim
                k.append('$').append(v.name());
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
                        rowVar, rowType,
                        Pipelines.materialize(subCs.pipeline(), Set.of(),
                                subCs.classFqn()).pipeline()));
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
        return com.legend.compiler.spec.typed.VarUse.reads(n, var);
    }
}
