package com.legend.resolver;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedConcatenate;
import com.legend.compiler.spec.typed.TypedFuncCol;
import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedJoin;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedProject;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.compiler.element.ModelContext;
import com.legend.error.MappingResolutionException;
import com.legend.error.NotImplementedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * UNION heads ({@code #uN}): the join material of a concatenate of
 * navigation chains through DIFFERENT head properties —
 * {@code $t.subAccount.oe->concatenate($t.otherAccount.oe).name}.
 *
 * <p>Engine semantics (processConcatenate, pureToSQLQuery.pure:2709;
 * buildConcatenateSubSelect :2889): each branch chain becomes one member
 * select, the members are aligned onto one column list (every member's
 * join keys ride the union, NULL in the members that do not own them —
 * alignJoinAndPkColumnsForUnion) and UNION ALL-ed into ONE
 * {@code unionalias_N} derived table, LEFT-joined on the OR of the branch
 * join conditions; the leaf reads the union's shared column. Rows
 * explode when several branches match (a LEFT join, never a coalesce).
 *
 * <p>Ours: member j = branch j's hop-0 target material (the association
 * route or the navigate-slot route, whichever the head is) with a second
 * hop chained INSIDE the member (a target navigate slot rides the SubNav,
 * an embedded ctor drills, an association hop LEFT-joins the member);
 * the union row = one column per demanded leaf property + every member's
 * condition keys aligned BY NAME across the members (the engine's
 * alignJoinAndPkColumnsForUnion: a key column the member lacks projects
 * NULL; two branches keyed on a same-named column SHARE it — the
 * ComplexReturnType golden joins {@code unionalias_0.ID = root.FIRMID or
 * unionalias_0.ID = root.ADDRESSID} over ONE {@code ID}, and its rows
 * include the cross matches); the head's condition = OR over members of
 * the branch condition re-pointed at the union row.
 */
final class UnionHeads {
    private final ModelContext ctx;
    private final ClassSources sources;
    private final SyntheticHeads synthetics;
    private final AssociationJoins joins;
    private final @com.legend.Nullable NavMaterializer navMaterializer;

    UnionHeads(ModelContext ctx, ClassSources sources,
            SyntheticHeads synthetics, AssociationJoins joins,
            @com.legend.Nullable NavMaterializer navMaterializer) {
        this.ctx = ctx;
        this.sources = sources;
        this.synthetics = synthetics;
        this.joins = joins;
        this.navMaterializer = navMaterializer;
    }

    /** One hop's target material, whichever route served it. */
    private record Hop(ClassSource target, TypedSpec pipeline,
                       Type.RelationType row, TypedLambda cond,
                       Map<String, String> slotPrefixes,
                       Map<String, Substitution.SubNav> subNavs) {}

    /** A leaf expression still spelled over its own row variable. */
    private record Read(TypedSpec expr, String rowVar, String prefix) {}

    /** One branch as a member relation: its pipe and row, the demanded
     * leaves as expressions over {@code m}, its hop-0 condition and the
     * target-side key columns that condition reads. */
    private record Member(TypedSpec pipe, Type.RelationType row,
                          Map<String, TypedSpec> leaves, TypedLambda cond,
                          List<String> keys) {}

    private static final String MEMBER_VAR = "m";
    private static final String UNION_VAR = "u_row";

    AssociationJoins.AssocJoin material(TemporalFrame temporal, ClassSource cs,
            String head, StoreResolver.Context context, Set<String> leaves) {
        SyntheticHeads.UnionSpec spec = synthetics.unionSpec(head);
        if (leaves.isEmpty()) {
            throw new NotImplementedException("concatenated navigation of '"
                    + spec.classFqn() + "' read as a whole value is not"
                    + " supported yet");
        }
        List<Member> members = new ArrayList<>(spec.paths().size());
        for (List<String> path : spec.paths()) {
            members.add(member(temporal, cs, path, context, leaves));
        }
        Type.RelationType urow = unionRow(members, leaves);
        var many = Multiplicity.Bounded.ZERO_MANY;
        TypedSpec union = null;
        for (int j = 0; j < members.size(); j++) {
            TypedSpec arm = new TypedProject(members.get(j).pipe(),
                    memberColumns(members, j, leaves, urow),
                    new ExprType(Type.relation(urow), many));
            union = union == null ? arm
                    : new TypedConcatenate(union, arm,
                            new ExprType(Type.relation(urow), many));
        }
        TypedLambda cond = orOfConditions(members, cs.rowType(), urow);
        Map<String, TypedSpec> bindings = new LinkedHashMap<>();
        var uInfo = new ExprType(urow, Multiplicity.Bounded.ONE);
        for (Type.Column c : urow.columns()) {
            if (leaves.contains(c.name())) {
                bindings.put(c.name(), new TypedPropertyAccess(
                        new TypedVariable(UNION_VAR, uInfo), c.name(),
                        new ExprType(c.type(), c.multiplicity())));
            }
        }
        ClassSource target = new ClassSource(cs.mappingFqn(), spec.classFqn(),
                ClassSource.UNION_SET_ID, java.util.Objects.requireNonNull(union), UNION_VAR,
                bindings, urow);
        return new AssociationJoins.AssocJoin(
                AssociationJoins.prefixFor(head, cs), target, union, urow,
                cond, Map.of(), Map.of(), null, null, false);
    }

    private Member member(TemporalFrame temporal, ClassSource cs,
            List<String> path, StoreResolver.Context context,
            Set<String> leaves) {
        String h0 = path.get(0);
        List<String> tail = path.subList(1, path.size());
        if (tail.size() > 1) {
            throw new NotImplementedException("concatenated navigation branch '$"
                    + String.join(".", path) + "' deeper than two hops is not"
                    + " supported yet");
        }
        Set<List<String>> navTails = new LinkedHashSet<>();
        if (!tail.isEmpty()) {
            for (String l : leaves) {
                List<String> t = new ArrayList<>(tail);
                t.add(l);
                navTails.add(t);
            }
        }
        Set<String> leaves0 = tail.isEmpty() ? leaves : Set.of(tail.get(0));
        Hop h = hop(temporal, cs, h0, context, leaves0, navTails);
        TypedSpec pipe = h.pipeline();
        Type.RelationType row = h.row();
        Map<String, Read> reads = new LinkedHashMap<>();
        if (tail.isEmpty()) {
            for (String l : leaves) {
                reads.put(l, new Read(leafBinding(h.target(), l, h.slotPrefixes(),
                        h.subNavs()), h.target().rowVar(), ""));
            }
        } else {
            String t0 = tail.get(0);
            Substitution.SubNav sn = h.subNavs().get(t0);
            TypedSpec tb = h.target().bindings().get(t0);
            if (sn != null) {
                // the target's own navigate slot, materialized INTO the
                // hop (its leaves ride the SubNav's prefix)
                for (String l : leaves) {
                    reads.put(l, new Read(requireLeaf(sn.bindings().get(l),
                            h.target().classFqn(), t0, l), sn.rowVar(), sn.prefix()));
                }
            } else if (tb != null
                    && Pipelines.unwrapToOne(tb) instanceof TypedNewInstance ctor) {
                // an EMBEDDED ctor on the target row: drill its fields
                for (String l : leaves) {
                    reads.put(l, new Read(requireLeaf(ctor.properties().get(l),
                            h.target().classFqn(), t0, l), h.target().rowVar(), ""));
                }
            } else {
                // an ASSOCIATION hop of the target: LEFT-joined INSIDE the
                // member (the member is the branch CHAIN, keyed by hop 0)
                AssociationJoins.AssocJoin aj1 = joins.associationJoin(temporal,
                        h.target(), t0, context, false, leaves, h0 + "." + t0,
                        Set.of());
                List<Type.Column> cols = new ArrayList<>(row.columns());
                for (Type.Column c : aj1.targetRow().columns()) {
                    cols.add(new Type.Column(aj1.prefix() + c.name(), c.type(),
                            c.multiplicity()));
                }
                row = new Type.RelationType(cols);
                pipe = new TypedJoin(pipe, aj1.targetPipeline(),
                        AssociationJoins.leftKind(),
                        java.util.Objects.requireNonNull(aj1.condition(),
                                "association hop without a condition"),
                        Optional.of(aj1.prefix()), null,
                        new ExprType(Type.relation(row), Multiplicity.Bounded.ONE),
                        false /* resolver-synth */);
                for (String l : leaves) {
                    reads.put(l, new Read(leafBinding(aj1.target(), l,
                            aj1.targetSlotPrefixes(), aj1.targetSubNavs()),
                            aj1.target().rowVar(), aj1.prefix()));
                }
            }
        }
        var mInfo = new ExprType(row, Multiplicity.Bounded.ONE);
        Map<String, TypedSpec> leafExprs = new LinkedHashMap<>();
        for (var e : reads.entrySet()) {
            Read r = e.getValue();
            leafExprs.put(e.getKey(), Pipelines.prefixColumns(r.expr(), r.rowVar(),
                    r.prefix(), v -> new TypedVariable(MEMBER_VAR, mInfo)));
        }
        Set<String> keys = new LinkedHashSet<>();
        TypedLambda cond = h.cond();
        for (TypedSpec b : cond.body()) {
            Pipelines.collectVarReads(b, cond.parameters().get(1), keys);
        }
        return new Member(pipe, row, leafExprs, cond, new ArrayList<>(keys));
    }

    /** A scalar leaf of {@code target}, its slot-backed reads flattened
     * onto the materialized row; a class-typed leaf is a further hop
     * (loud — the union projects VALUES). */
    private static TypedSpec leafBinding(ClassSource target, String leaf,
            Map<String, String> slotPrefixes,
            Map<String, Substitution.SubNav> subNavs) {
        TypedSpec b = requireLeaf(target.bindings().get(leaf), target.classFqn(),
                null, leaf);
        if (Type.asClassType(Pipelines.unwrapToOne(b).info().type()) instanceof Type.ClassType
                || Pipelines.unwrapToOne(b) instanceof TypedNewInstance) {
            throw new NotImplementedException("concatenated navigation leaf '"
                    + leaf + "' of '" + target.classFqn()
                    + "' is class-typed — a further hop past the union is not"
                    + " supported yet");
        }
        if (!slotPrefixes.isEmpty()) {
            Map<String, String> slotOnly = new LinkedHashMap<>(slotPrefixes);
            slotOnly.keySet().removeAll(subNavs.keySet());
            if (!slotOnly.isEmpty()) {
                b = Pipelines.rewriteRowReads(b, target.rowVar(), slotOnly,
                        Set.of(), java.util.function.UnaryOperator.identity());
            }
        }
        return b;
    }

    private static TypedSpec requireLeaf(@com.legend.Nullable TypedSpec b,
            String classFqn, @com.legend.Nullable String via, String leaf) {
        if (b == null) {
            throw new MappingResolutionException("property '" + leaf
                    + "' of class '" + classFqn + "'"
                    + (via == null ? "" : " (through '" + via + "')")
                    + " is not mapped", classFqn);
        }
        return b;
    }

    /** Hop 0 of a branch: the association route, or the navigate-slot
     * route for a class-typed Join PM head (the corrNavHeads shape —
     * NavMaterializer target material + the slot's own predicate). */
    private Hop hop(TemporalFrame temporal, ClassSource cs, String h0,
            StoreResolver.Context context, Set<String> leaves0,
            Set<List<String>> navTails) {
        TypedSpec binding = cs.bindings().get(h0);
        if (binding == null) {
            AssociationJoins.AssocJoin aj = joins.associationJoin(temporal, cs,
                    h0, context, false, leaves0, h0, navTails);
            return new Hop(aj.target(), aj.targetPipeline(), aj.targetRow(),
                    java.util.Objects.requireNonNull(aj.condition(),
                            "association join without a condition"),
                    aj.targetSlotPrefixes(), aj.targetSubNavs());
        }
        var navSteps = Pipelines.navSteps(cs.pipeline());
        String alias = InnerDemand.navSlotAlias(binding, cs.rowVar(),
                navSteps.keySet());
        if (alias == null || navMaterializer == null) {
            throw new NotImplementedException("concatenated navigation through"
                    + " '" + h0 + "' of '" + cs.classFqn()
                    + "' (an embedded / inline head) is not supported yet");
        }
        var nav = java.util.Objects.requireNonNull(navSteps.get(alias));
        if (!(nav.target() instanceof TypedGetAll tg)) {
            throw new NotImplementedException("concatenated navigation through"
                    + " '" + h0 + "' of '" + cs.classFqn()
                    + "' (a non-class navigate target) is not supported yet");
        }
        ClassSource target = sources.get(cs.mappingFqn(), tg.classFqn(), cs.scope());
        NavMaterializer.NavMat mat = navMaterializer.navTargetMaterialized(
                temporal, cs.mappingFqn(), tg.classFqn(), cs.scope(),
                new ArrayList<>(navTails), h0, TemporalContext.NONE);
        TypedSpec tPipe = temporal.temporalTargetPipe(cs, target, h0,
                temporal.applyJoinTemporalFilters(mat.pipeline(), target,
                        Map.of()));
        return new Hop(target, tPipe,
                Type.requireRelationSchema(tPipe.info().type()), nav.predicate(),
                mat.slotPrefixes(), mat.subNavs());
    }

    /** The aligned column list: leaves first (typed by member 0's
     * expression, nullable — a LEFT-joined union), then the members'
     * keys by NAME (first owner types it; a leaf/key name clash is loud —
     * the engine spells the leaf {@code <table><COL>}, we keep the
     * property name). */
    private static Type.RelationType unionRow(List<Member> members,
            Set<String> leaves) {
        List<Type.Column> cols = new ArrayList<>();
        var opt = Multiplicity.Bounded.ZERO_ONE;
        for (String l : leaves) {
            TypedSpec e0 = java.util.Objects.requireNonNull(
                    members.get(0).leaves().get(l));
            cols.add(new Type.Column(l, e0.info().type(), opt));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Member m : members) {
            for (String k : m.keys()) {
                if (leaves.contains(k)) {
                    throw new NotImplementedException("concatenated navigation:"
                            + " join key column '" + k + "' collides with the"
                            + " demanded leaf of the same name");
                }
                if (seen.add(k)) {
                    cols.add(new Type.Column(k, column(m.row(), k).type(), opt));
                }
            }
        }
        return new Type.RelationType(cols);
    }

    private static Type.Column column(Type.RelationType row, String name) {
        return row.columns().stream().filter(c -> c.name().equals(name))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "resolver bug: union member key '" + name
                        + "' missing from its row"));
    }

    /** Member {@code j} projected onto the union row: its leaf
     * expressions, its own keys by name, NULL for the keys it lacks. */
    private static List<TypedFuncCol> memberColumns(List<Member> members,
            int j, Set<String> leaves, Type.RelationType urow) {
        Member m = members.get(j);
        var mInfo = new ExprType(m.row(), Multiplicity.Bounded.ONE);
        List<TypedFuncCol> cols = new ArrayList<>();
        for (String l : leaves) {
            cols.add(col(l, java.util.Objects.requireNonNull(m.leaves().get(l)),
                    m.row()));
        }
        for (Type.Column uc : urow.columns()) {
            if (leaves.contains(uc.name())) {
                continue;
            }
            TypedSpec v = m.keys().contains(uc.name())
                    ? new TypedPropertyAccess(new TypedVariable(MEMBER_VAR, mInfo),
                            uc.name(), new ExprType(uc.type(), uc.multiplicity()))
                    : new TypedCollection(List.of(),
                            new ExprType(uc.type(), Multiplicity.Bounded.ZERO_ONE));
            cols.add(col(uc.name(), v, m.row()));
        }
        return cols;
    }

    private static TypedFuncCol col(String name, TypedSpec value,
            Type.RelationType mRow) {
        var one = Multiplicity.Bounded.ONE;
        var lFn = new Type.FunctionType(
                List.of(new Type.Param(mRow, one)),
                new Type.Param(value.info().type(), value.info().multiplicity()));
        return new TypedFuncCol(name, new TypedLambda(List.of(MEMBER_VAR),
                List.of(value), new ExprType(lFn, one)));
    }

    /** {@code (s, t) | cond_0 or cond_1 ...} over the parent row and the
     * union row (each branch condition's own params renamed). */
    private TypedLambda orOfConditions(List<Member> members,
            Type.RelationType srcRow, Type.RelationType urow) {
        var one = Multiplicity.Bounded.ONE;
        var boolOne = new ExprType(Type.Primitive.BOOLEAN, one);
        var s = new TypedVariable("s", new ExprType(srcRow, one));
        var t = new TypedVariable("t", new ExprType(urow, one));
        TypedSpec or = null;
        for (int j = 0; j < members.size(); j++) {
            TypedLambda c = members.get(j).cond();
            TypedSpec body = c.body().get(c.body().size() - 1);
            TypedSpec re = retarget(body, c.parameters().get(0), s,
                    c.parameters().get(1), t);
            or = or == null ? re : new TypedNativeCall(orFn(), List.of(or, re), boolOne);
        }
        return new TypedLambda(List.of("s", "t"),
                List.of(java.util.Objects.requireNonNull(or)),
                new ExprType(new Type.FunctionType(
                        List.of(new Type.Param(srcRow, one), new Type.Param(urow, one)),
                        new Type.Param(Type.Primitive.BOOLEAN, one)), one));
    }

    /** Re-point the condition's reads of its own two row variables onto
     * {@code s} / {@code t} (Pipelines.prefixColumns with an empty column
     * prefix — the one re-pointing walker). */
    private static TypedSpec retarget(TypedSpec n, String sv, TypedVariable s,
            String tv, TypedVariable t) {
        TypedSpec onS = Pipelines.prefixColumns(n, sv, "", v -> s);
        return Pipelines.prefixColumns(onS, tv, "", v -> t);
    }

    private com.legend.compiler.element.TypedFunction orFn() {
        var fns = ctx.findFunction("meta::pure::functions::boolean::or")
                .stream().filter(f -> f.parameters().size() == 2).toList();
        if (fns.size() != 1) {
            throw new IllegalStateException(
                    "resolver bug: expected one 2-arg boolean::or");
        }
        return fns.get(0);
    }
}
