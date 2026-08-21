package com.legend.resolver;

import com.legend.compiler.element.MilestoningStrategy;
import com.legend.builtin.Pure;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedAggCol;
import com.legend.compiler.spec.typed.TypedAggregate;
import com.legend.compiler.spec.typed.TypedCBoolean;
import com.legend.compiler.spec.typed.TypedCFloat;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedConcatenate;
import com.legend.compiler.spec.typed.TypedDistinct;
import com.legend.compiler.spec.typed.TypedDrop;
import com.legend.compiler.spec.typed.TypedEnumValue;
import com.legend.compiler.spec.typed.TypedExtend;
import com.legend.compiler.spec.typed.TypedExtendAgg;
import com.legend.compiler.spec.typed.TypedExtendWindow;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedFuncCol;
import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedGraphFetch;
import com.legend.compiler.spec.typed.TypedGraphTree;
import com.legend.compiler.spec.typed.TypedGroupBy;
import com.legend.compiler.spec.typed.TypedIf;
import com.legend.compiler.spec.typed.TypedJoin;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedLimit;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedMilestonedAccess;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNavigate;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedProject;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedRename;
import com.legend.compiler.spec.typed.TypedSelect;
import com.legend.compiler.spec.typed.TypedSerialize;
import com.legend.compiler.spec.typed.TypedSerializeGraph;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedSort;
import com.legend.compiler.spec.typed.TypedSortBy;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.MappingResolutionException;
import com.legend.error.NotImplementedException;
import com.legend.model.RuntimeDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
/**
 * Phase H &mdash; the pure {@code TypedSpec -> TypedSpec} rewriter replacing
 * object-space class queries with relation pipelines resolved against the
 * active mapping (contract: {@link com.legend.resolver} package doc; design:
 * {@code docs/PHASE_H2_H3_RESOLVER_PLAN.md}).
 *
 * <p>H2 scope: {@code getAll -> [filter|limit|take|slice|drop]* -> project}
 * chains over a single class. The projection boundary exits object space
 * with its {@code info} UNCHANGED, so everything downstream (relation
 * space) passes through untouched. Unsupported object-space constructs are
 * loud, naming the construct and the owning phase.
 */
public final class StoreResolver {

    private final ClassSources sources;
    private final SpecCompiler specs;
    private int freshVarCounter;
    /** Synthetic head registry ('#f'/'#d'/'#c') — append-only. */
    private final SyntheticHeads synthetics;
    /** Query-body lets, shared by reference with every TemporalFrame. */
    private final Map<String, TypedSpec> letBindings =
            new java.util.LinkedHashMap<>();

    /** Pre-seed the let env with bindings a caller already consumed
     * (the inliner β-reduces query lets, but graph-tree date args keep
     * their source spelling and resolve here — engine inScopeVars). */
    public StoreResolver withLetBindings(Map<String, TypedSpec> lets) {
        letBindings.putAll(lets);
        return this;
    }

    private GraphEmission.@com.legend.Nullable SerializeTypeConfig serializeTypeCfg;
    private boolean checkedEnvelope;   // graphFetchChecked defect gate
    /** Recursive navigate-target materialization (stateless service). */
    private final NavMaterializer navMaterializer;
    /** Association-route join material (stateless service). */
    private final AssociationJoins assocMaterial;
    private final CorrelatedSubselects corrSubs;
    /** THE per-resolution temporal frame (root context + chain specs +
     * stamping machinery) — set at op-chain collection, specs attached
     * after the demand scan; nested sibling resolutions overwrite at
     * their own entry (audit 10 semantics). */
    private TemporalFrame temporal;

    private final ModelContext ctx;

    public StoreResolver(ModelContext ctx, SpecCompiler specs) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.specs = Objects.requireNonNull(specs, "specs");
        this.sources = new ClassSources(ctx, specs);
        this.synthetics = new SyntheticHeads(ctx);
        // an EMPTY frame until the op-chain phase constructs the real one —
        // pre-resolution consumers (lift walkers, resolveNode shape checks)
        // see NO context, exactly the old fields' initial values
        this.temporal = new TemporalFrame(ctx, sources, TemporalContext.NONE,
                Map.of(), letBindings);
        this.assocMaterial = new AssociationJoins(ctx, sources, specs,
                synthetics);
        this.corrSubs = new CorrelatedSubselects(sources, assocMaterial);
        this.navMaterializer = new NavMaterializer(sources, assocMaterial,
                synthetics, corrSubs);
    }

    public List<TypedSpec> resolve(List<TypedSpec> body) { return resolve(body, null); }

    /**
     * Resolve with a DRIVER-SUPPLIED execution context (the corpus shape:
     * queries carry no {@code ->from(...)}; the runtime arrives via the
     * service API). Precedence per the plan: an explicit {@code from()} in
     * the query always wins; the driver runtime is the outermost fallback.
     */
    public List<TypedSpec> resolve(List<TypedSpec> body,
            @com.legend.Nullable String driverRuntimeFqn) { return resolve(body, driverRuntimeFqn, null); }

    /** {@code explicitMappingFqn}: resolve class fetches against THIS
     * mapping (the ~func-pipeline recursion — ClassSources) — an explicit
     * from() in the body still wins. */
    public List<TypedSpec> resolve(List<TypedSpec> body,
            @com.legend.Nullable String driverRuntimeFqn,
            @com.legend.Nullable String explicitMappingFqn) {
        return resolve(body, driverRuntimeFqn, explicitMappingFqn,
                List.of());
    }

    /** {@code chainMappings}: ModelChainConnection mapping FQNs from the
     * DRIVER runtime (execute threads via TypedFrom; plan-text here). */
    public List<TypedSpec> resolve(List<TypedSpec> body,
            @com.legend.Nullable String driverRuntimeFqn,
            @com.legend.Nullable String explicitMappingFqn,
            List<String> chainMappings) {
        // LAZY: runtime consulted only when a class fetch needs a mapping
        // (a pure relation query must not — date-literal regression).
        // the RUNTIME rides ALONGSIDE an explicit mapping (self-sourced
        // M2M upstream dispatch needs the candidate set)
        Context context = explicitMappingFqn != null
                ? new Context(explicitMappingFqn, driverRuntimeFqn,
                        chainMappings)
                : driverRuntimeFqn == null ? Context.NONE
                : Context.ofRuntime(driverRuntimeFqn);
        List<TypedSpec> out = new ArrayList<>(body.size());
        for (TypedSpec stmt : body) {
            // milestoning-date let env (engine inScopeVars, M:648):
            // shared by reference with every TemporalFrame
            if (stmt instanceof com.legend.compiler.spec.typed.TypedLet l) {
                letBindings.put(l.name(), l.value());
            }
            out.add(resolveNode(stmt, context));
        }
        for (int i = 0; i < out.size(); i++) {
            out.set(i, onFormPass(out.get(i), andCallee()));
        }
        for (TypedSpec stmt : out) {
            assertNoStoreOnlyEscapees(stmt);
        }
        return out;
    }

    /** ENGINE ON-FORM post-pass (memory milestoning-onclause-seam): a
     * LEFT/INNER join whose RIGHT side is temporal-STAMP filter layers
     * (STAMP_ROW_VAR-marked) hoists them into its ON — the window spells
     * in the join condition, the pipe joins raw. Runs over the FULLY
     * resolved tree, after every consumer decision (the grouped routes'
     * internals hoist identically — same rows, engine shape). */
    private static TypedSpec onFormPass(TypedSpec n,
            com.legend.compiler.element.TypedFunction andFn) {
        n = n.mapChildren(c -> onFormPass(c, andFn));
        if (n instanceof TypedJoin j
                && ("LEFT".equals(j.kind().value())
                        || "INNER".equals(j.kind().value()))) {
            Object[] r = Pipelines.onFormRelocate(j.right(), j.condition(),
                    andFn);
            if (r[0] != j.right()) {
                return new TypedJoin(j.left(), (TypedSpec) r[0], j.kind(),
                        (com.legend.compiler.spec.typed.TypedLambda) r[1],
                        j.prefix(), j.frameName(), j.info(),
                j.userCondition() /* rebuild */);
            }
        }
        return n;
    }

    /** The one resolved 2-arg {@code boolean::and} — the ON-form pass's
     * conjunction builder. */
    private com.legend.compiler.element.TypedFunction andCallee() {
        var fns = ctx.findFunction("meta::pure::functions::boolean::and")
                .stream().filter(f -> f.parameters().size() == 2).toList();
        if (fns.size() != 1) {
            throw new IllegalStateException(
                    "resolver bug: expected one 2-arg boolean::and");
        }
        return fns.get(0);
    }

    /** POST-CONDITION (core/README rule 9) — {@link StoreEscapees}. */
    static void assertNoStoreOnlyEscapees(TypedSpec n) {
        StoreEscapees.check(n);
    }

    /**
     * The execution context: an explicit mapping, or a runtime whose
     * candidate mappings are dispatched PER FETCHED CLASS — the candidate
     * that binds the class wins; zero or several binders is loud (plan
     * audit catch 1's precedence rule).
     */
    record Context(@com.legend.Nullable String explicitMapping,
            @com.legend.Nullable String runtimeFqn, List<String> chainMappings,
            Map<String, String> jsonSources) {
        Context(@com.legend.Nullable String explicitMapping,
                @com.legend.Nullable String runtimeFqn) { this(explicitMapping, runtimeFqn, List.of(), Map.of()); }
        Context(@com.legend.Nullable String explicitMapping, @com.legend.Nullable String runtimeFqn,
                List<String> chainMappings) {
            this(explicitMapping, runtimeFqn, chainMappings, Map.of());
        }
        static final Context NONE = new Context(null, null);
        static Context ofMapping(String fqn) { return new Context(fqn, null); }
        static Context ofRuntime(String fqn) { return new Context(null, fqn); }
        boolean isNone() { return explicitMapping == null && runtimeFqn == null; }
    }

    // =====================================================================
    // The context walk
    // =====================================================================

    /** if() over class queries: the condition must be STATICALLY
     * decidable — the chosen branch's thunk body resolves. */
    private @com.legend.Nullable TypedSpec resolveStaticIf(TypedIf i, Context context) {
        Boolean cond = LiteralFolds.staticBool(i.condition());
        if (cond == null) {
            throw new NotImplementedException("class query under if()"
                    + " with a runtime condition is not resolvable yet");
        }
        TypedSpec branch = cond ? i.thenBranch()
                : i.elseBranch().orElseThrow(() -> new NotImplementedException(
                        "class query under if() without an else branch"));
        return resolveNode(LiteralFolds.unthunk(branch), context);
    }

    /**
     * TWO-LEVEL dispatch (remediation T3.1): a handful of space-independent
     * normalizations, then an EXHAUSTIVE switch on the node's decided
     * {@link Space} — OBJECT resolves as (part of) a chain, ANCHORED
     * dispatches by variant, INERT is the identity. Guard ORDER is now
     * structure: an arm's precedence is the space level it lives in.
     */
    private TypedSpec resolveNode(TypedSpec n, Context context) {
        // ---- space-independent normalizations (fire in ANY space) ----
        // withFeatureFlags = IDENTITY (executionPlanFeature.pure:27)
        if (n instanceof TypedNativeCall wf
                && "meta::pure::executionPlan::featureFlag::withFeatureFlags"
                        .equals(wf.callee().qualifiedName())
                && !wf.args().isEmpty()) {
            return resolveNode(wf.args().get(0), context);
        }
        if (n instanceof TypedFrom from) {
            Context inner = fromContext(from, context);
            // in-query CLASS SUBQUERIES under lambdas lift FIRST
            // (SubQueryLift): the sub-chain resolves under THIS
            // from()'s context into a [0..1] scalar-subquery relation
            TypedSpec liftedSrc = SubQueryLift.lift(from.source(),
                    inner, ctx, specs, letBindings);
            return new TypedFrom(resolveNode(liftedSrc, inner),
                    from.mapping(), from.runtime(),
                    from.chainMappings(), from.jsonSources(),
                    from.sqlSetups(), from.connectionName(), from.info());
        }
        // zip over two projections of ONE source -> two-column project
        if (n instanceof TypedMap zm
                && zm.source() instanceof TypedNativeCall zc
                && "meta::pure::functions::collection::zip".equals(
                        zc.callee().qualifiedName())
                && zc.args().size() == 2) {
            TypedSpec zp = CorrelatedSubselects.zipPairMap(zm, zc,
                    n2 -> resolveNode(n2, context));
            if (zp != null) {
                return zp;
            }
        }
        // `.rows` MARKER erases here (audit 20c H1) — any space.
        if (n instanceof TypedPropertyAccess mk
                && mk.property().equals(com.legend.compiler
                        .element.type.PlatformTypes.ROWS_MARKER)
                && mk.source().info().type() instanceof Type.RelationType) {
            return resolveNode(mk.source(), context);
        }
        return switch (anchors.spaceOf(n)) {
            case OBJECT -> objectNode(n, context);
            case ANCHORED -> anchoredNode(n, context);
            case INERT -> n;   // no class fetch anywhere beneath
        };
    }

    /** An OBJECT-space node: by the spine rules the only non-chain shape
     * is the CLASS-RESULT mapper — the auto-map flatten IS the mapper body
     * with the source spliced for the param; the resulting hop chain
     * re-enters resolution. Everything else is a chain segment. */
    private TypedSpec objectNode(TypedSpec n, Context context) {
        return n instanceof TypedMap m
                ? resolveNode(substituteParam(m.mapper(), m.source()), context)
                : resolveChain(n, context);
    }

    /** ANCHORED: not itself object-space, but an unresolved anchor sits
     * beneath — chain TERMINALS first, then relation-space wrappers; an
     * unhandled variant is the NAMED H2-vocabulary wall, never a silent
     * pass-through. */
    private TypedSpec anchoredNode(TypedSpec n, Context context) {
        return switch (n) {
            case TypedProject p when objectSpace(p.source()) ->
                    resolveChain(p, context);
            // project DISTRIBUTES over a class-collection concatenate
            // (UNION ALL): each side is its own object-space chain
            case TypedProject p when classConcatOf(p.source()) != null -> {
                TypedNativeCall c = java.util.Objects.requireNonNull(
                        classConcatOf(p.source()));
                yield new TypedConcatenate(
                        resolveNode(new TypedProject(c.args().get(0), p.columns(),
                                p.info()), context),
                        resolveNode(new TypedProject(c.args().get(1), p.columns(),
                                p.info()), context),
                        p.info());
            }
            case TypedIf i -> {
                TypedSpec st = resolveStaticIf(i, context);
                if (st == null) {   // pre-gate: silent null for non-static if
                    throw new com.legend.error.NotImplementedException(
                            "class-typed if with a non-static condition"
                            + " is not supported yet");
                }
                yield st;
            }
            // size()/count() over a class extent = row count (engine
            // emits select count(*)); classExtentCount projects ONE const
            case TypedNativeCall nc
                    when nc.args().size() == 1 && objectSpace(nc.args().get(0))
                    && (nc.callee().qualifiedName().equals(
                                    "meta::pure::functions::collection::size")
                            || nc.callee().qualifiedName().equals(
                                    "meta::pure::functions::collection::count")) ->
                    classExtentCount(nc, context);
            // ->map(p|$p.scalarExpr) over instances = single-column
            // projection (map-terminal invariant)
            case TypedMap m when objectSpace(m.source()) -> {
                TypedMap m2 = synthetics.liftValueMapFilter(m);
                yield resolveChain(scalarMapAsProject(m2.source(), m2.mapper(),
                        m2.info().multiplicity()), context);
            }
            case TypedFilter f
                    when anchored(f.source())
                    && !(f.source().info().type() instanceof Type.ClassType)
                    && !(f.source().info().type() instanceof Type.RelationType)
                    && f.source() instanceof TypedPropertyAccess ->
                    foldScalarHopFilter(f, context);
            case TypedPropertyAccess pa when objectSpace(pa.source())
                    && !(pa.info().type() instanceof Type.ClassType) ->
                    scalarReadAsProject(pa, context);
            // Class-source groupBy (tds::groupBy cl:C[*] overload; the legacy
            // 4-arg form desugars into it): a relation-shaping TERMINAL like
            // project — key/map lambdas read the object and substitute
            // through the one funnel (plan: uniform lifting set). aggregate
            // is Relation-only in real pure — no class-source arm exists.
            case TypedGroupBy g when objectSpace(g.source()) ->
                    resolveChain(g, context);
            // serialize / graphFetch->serialize: the GRAPH terminal —
            // the graphFetch wrapper is source-preserving; the tree governs.
            case TypedSerialize sz when anchored(sz.source()) ->
                    resolveChain(sz, context);
            // RELATION-SPACE WRAPPERS above a class chain: every child
            // resolves structurally (a bare lambda is DATA — its arm below
            // is identity, so predicates/mappers/keys pass through
            // verbatim) and the variant rebuilds through its own
            // withChildren inverse (remediation T2.1).
            case TypedPropertyAccess pa
                    when anchored(pa.source())
                    && pa.source().info().type()
                            instanceof Type.RelationType ->
                    structural(pa, context);
            case TypedFilter f when anchored(f.source()) ->
                    structural(f, context);
            case TypedProject p when anchored(p.source()) ->
                    structural(p, context);
            case TypedSort s when anchored(s.source()) ->
                    structural(s, context);
            case TypedCast c
                    when anchored(c.source())
                    && c.info().type() instanceof Type.RelationType ->
                    structural(c, context);
            case TypedSortBy sb when anchored(sb.source()) ->
                    structural(sb, context);
            case TypedLimit l when anchored(l.source()) ->
                    structural(l, context);
            case TypedDrop d when anchored(d.source()) ->
                    structural(d, context);
            case TypedSlice s when anchored(s.source()) ->
                    structural(s, context);
            case TypedDistinct d when anchored(d.source()) ->
                    structural(d, context);
            case TypedGroupBy g when anchored(g.source()) ->
                    structural(g, context);
            case TypedAggregate a when anchored(a.source()) ->
                    structural(a, context);
            case TypedExtend e when anchored(e.source()) ->
                    structural(e, context);
            case TypedExtendWindow w when anchored(w.source()) ->
                    structural(w, context);
            case TypedExtendAgg e when anchored(e.source()) ->
                    structural(e, context);
            case TypedRename r when anchored(r.source()) ->
                    structural(r, context);
            case TypedSelect s when anchored(s.source()) ->
                    structural(s, context);
            case TypedConcatenate c -> structural(c, context);
            // navigate keeps its TARGET verbatim (the navigation pipeline
            // is resolver OUTPUT vocabulary) — only the source resolves
            case TypedNavigate nav
                    when anchored(nav.source())
                    && nav.target().info().type()
                            instanceof Type.RelationType ->
                    new TypedNavigate(
                            resolveNode(nav.source(), context), nav.alias(),
                            nav.target(), nav.predicate(), nav.pairedPredicate(),
                            nav.frameName(), nav.form(), nav.info());
            case TypedJoin j -> structural(j, context);
            // map over RELATION rows above a class chain (the object-space
            // map arms matched earlier; this is the relation-space wrapper)
            case TypedMap m
                    when anchored(m.source())
                    && m.source().info().type()
                            instanceof Type.RelationType ->
                    structural(m, context);
            // scalar/relation NATIVES over chains bottoming at a getAll:
            // args resolve structurally; CLASS-typed emptiness rewrites
            // FIRST (constant-project relation -> lowerer EXISTS; map §2).
            case TypedNativeCall nc ->
                    structural(Pipelines.classEmptinessRewrite(nc,
                            this::objectSpace), context);
            // collection literal whose ELEMENTS carry class chains:
            // each element resolves independently, structurally
            case com.legend.compiler.spec.typed.TypedCollection col ->
                    structural(col, context);
            // a CAST over a chain bottoming at a getAll (typed reads like
            // getFloat = cast(columnRead(chain))): the source resolves
            // structurally, the cast rides along
            case com.legend.compiler.spec.typed.TypedCast tc ->
                    structural(tc, context);
            case TypedPropertyAccess vpa   // genericType().rawType (M3)
                    when GenericTypeReflection.matches(vpa) ->
                    GenericTypeReflection.resolve(vpa, x -> resolveNode(x, context),
                            f -> sources.get(dispatch(context, f), f).pipeline(),
                            ctx.elementFqns());
            // BARE value read over a class chain = auto-map sugar (Pipelines)
            case TypedPropertyAccess vpa when anchored(vpa.source()) -> {
                TypedSpec am = Pipelines.autoMapRead(vpa);
                if (am == null) {
                    throw new NotImplementedException("class query under"
                            + " TypedPropertyAccess is not resolvable yet"
                            + " (H2 vocabulary)");
                }
                yield resolveNode(am, context);
            }
            // a BARE lambda VALUE is DATA — but a SELF-CONTAINED query
            // beneath it has no other owner (SubQueryLift.resolveClosed
            // javadoc): resolve those, leave param-dependent reads as data
            case com.legend.compiler.spec.typed.TypedLambda l ->
                    l.mapChildren(b -> SubQueryLift.resolveClosed(b,
                            new java.util.LinkedHashSet<>(l.parameters()),
                            r -> resolveNode(r, context)));
            // The NAMED wall: an ANCHORED variant with no arm — loud,
            // never a silent pass-through (the old default's silent
            // 'yield n' path is now the INERT level).
            default -> throw new NotImplementedException("class query under "
                    + n.getClass().getSimpleName()
                    + " is not resolvable yet (H2 vocabulary)");
        };
    }

    /** Relation-space wrapper rebuild: children resolve, withChildren
     * reassembles — no field can be re-founded by hand. */
    private TypedSpec structural(TypedSpec n, Context context) {
        return n.mapChildren(k -> resolveNode(k, context));
    }

    /** The element CLASS of an object-space chain (for synthetic lambdas). */
    /** The NAV-SLOT correlation pass: a demanded navigate step whose lifted
     * head carries a CORRELATED predicate gets it ANDed into the step\u0027s
     * own condition (parentRow, targetTableRow — both in scope) BEFORE
     * materialization, via the association route\u0027s exact composition. */
    private TypedSpec augmentNavPredicates(TypedSpec pipe, ClassSource cs,
            Map<String, String> navHeadByAlias, Set<String> demandedNavs,
            Set<String> composed,
            Map<String, Substitution.AssocSub> parentAssocs,
            Map<String, NavMaterializer.NavMat> navMats) {
        // audit 21b F1: this walk must reach every navigate step
        // Pipelines.navSteps reaches — a scalar-through-join PM declared
        // after the class-typed Join PM leaves the TypedNavigate below a
        // TypedJoinSlot, and skipping it silently DROPPED the correlated
        // conjunct (PM-declaration-order wrong rows). The spine arms here
        // mirror navSteps' node set; materializeRoot's composed-or-loud
        // check backstops any spine node this walk still misses.
        if (pipe instanceof TypedNavigate nav && nav.alias().isPresent()) {
            TypedSpec src = augmentNavPredicates(nav.source(), cs,
                    navHeadByAlias, demandedNavs, composed, parentAssocs,
                    navMats);
            String head = navHeadByAlias.getOrDefault(nav.alias().get(),
                    nav.alias().get());
            TypedLambda corr = synthetics.correlatedPred(head);
            if (corr != null && demandedNavs.contains(nav.alias().get())
                    && nav.target() instanceof TypedGetAll ga) {
                ClassSource target = sources.get(cs.mappingFqn(), ga.classFqn());
                NavMaterializer.NavMat mat = navMats.get(nav.alias().get());
                TypedLambda aug = assocMaterial.andCorrelatedIntoCondition(
                        nav.predicate(), corr, cs, target,
                        mat != null ? mat.slotPrefixes() : Map.of(),
                        parentAssocs,
                        mat != null ? mat.subNavs() : Map.of());
                composed.add(nav.alias().get());
                return new TypedNavigate(src, nav.alias(), nav.target(),
                        aug, nav.pairedPredicate(), nav.frameName(),
                        nav.form(), nav.info());
            }
            return src == nav.source() ? pipe
                    : new TypedNavigate(src, nav.alias(), nav.target(),
                            nav.predicate(), nav.pairedPredicate(),
                            nav.frameName(), nav.form(), nav.info());
        }
        if (pipe instanceof TypedFilter f) {
            TypedSpec src = augmentNavPredicates(f.source(), cs,
                    navHeadByAlias, demandedNavs, composed, parentAssocs,
                    navMats);
            return src == f.source() ? pipe
                    : new TypedFilter(src, f.predicate(), f.info());
        }
        if (pipe instanceof com.legend.compiler.spec.typed.TypedJoinSlot js) {
            TypedSpec src = augmentNavPredicates(js.source(), cs,
                    navHeadByAlias, demandedNavs, composed, parentAssocs,
                    navMats);
            return src == js.source() ? pipe
                    : new com.legend.compiler.spec.typed.TypedJoinSlot(src,
                            js.alias(), js.target(), js.condition(), js.frameName(), js.info());
        }
        return pipe;
    }

    /** A CORRELATED lifted predicate is only applicable at the association
     * route's join CONDITION (both rows in scope). Every OTHER consumer of
     * a synthetic head must refuse LOUDLY — applying only the closed
     * predicates would silently DROP the correlation (wrong rows). */
    private void requireNoCorrelatedPred(String head, String where) {
        if (synthetics.correlatedPred(head) != null) {
            throw new NotImplementedException("correlated filtered navigation"
                    + " '" + SyntheticHeads.realHead(head) + "' is not"
                    + " supported on the " + where + " route yet (the"
                    + " predicate reads the outer row)");
        }
    }

    /** A scalar property read over an object-space chain as the
     * single-column projection: EMBEDDED (non-assoc) class-hop prefixes
     * peel INTO the reading lambda (the funnel's embedded dispatch owns
     * them); ASSOCIATION hops stay in the chain for the flatten
     * (collectOpChain re-roots at their target). */
    private TypedSpec scalarReadAsProject(TypedPropertyAccess pa,
            Context context) {
        java.util.Deque<TypedPropertyAccess> path = new java.util.ArrayDeque<>();
        TypedSpec src = pa.source();
        // ALL class hops (association AND embedded) peel into the lambda:
        // the funnel's positional rules own scalar path explosion - it
        // routes associations through the full demand machinery (union
        // dispatch, otherwise, navigate slots), which the flatten's direct
        // AssociationBinding lookup cannot yet match. The flatten serves
        // only the CLASS-RESULT shapes (bare class root, class-result
        // maps), where no consuming lambda exists.
        while (src instanceof TypedPropertyAccess hp
                && hp.info().type() instanceof Type.ClassType) {
            path.addFirst(hp);
            src = hp.source();
        }
        Type rootClass = sourceClassType(src);
        TypedSpec read = new TypedVariable("p", ExprType.one(rootClass));
        for (TypedPropertyAccess hp : path) {
            read = new TypedPropertyAccess(read, hp.property(), hp.info());
        }
        read = new TypedPropertyAccess(read, pa.property(), pa.info());
        TypedLambda fn = new TypedLambda(List.of("p"), List.of(read),
                new ExprType(
                        new Type.FunctionType(
                                List.of(new Type.Param(rootClass,
                                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                                new Type.Param(pa.info().type(),
                                        pa.info().multiplicity())),
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
        return resolveChain(scalarMapAsProject(src, fn,
                pa.info().multiplicity()), context);
    }

    /** The NAVIGATE-SLOT flatten route: materialize the source pipeline
     * with the hop's TypedNavigate step DEMANDED (the same machinery the
     * funnel uses), then re-root at the step's target class with bindings
     * re-pointed through the slot prefix. */
    private ClassSource flattenNavSlot(ClassSource src, String alias,
            TypedNavigate step, Set<String> downstreamHeads,
            Map<String, Substitution.AssocSub> provOut,
            List<TypedSpec> belowOps, Context context,
            boolean rowPreserving) {
        if (!(step.target() instanceof TypedGetAll tg)) {
            throw new NotImplementedException("class flatten through a"
                    + " CHAINED navigate step ('" + alias
                    + "') is not supported yet");
        }
        String targetClass = tg.classFqn();
        ClassSource t = sources.get(src.mappingFqn(), targetClass);
        // DOWNSTREAM demand (#63): heads read off the re-rooted target
        // dispatch through its OWN nav/slot steps — materialize them INTO
        // the hop (composed prefixes employees_firm_*); provenance
        // AssocSubs give the dispatch route. Un-demanded steps strip.
        var tNavSteps = Pipelines.navSteps(t.pipeline());
        Set<String> tSlots = Pipelines.slotAliases(t.pipeline());
        Set<String> tSlotDemand = new LinkedHashSet<>();
        Set<String> tNavDemand = new LinkedHashSet<>();
        Map<String, String> headNavAlias = new LinkedHashMap<>();
        // hop-colliding below-ops HOIST above the materialization; their
        // tail heads extend the hop's own demand (leg slice 2)
        FlattenOps.BelowSplit bsp = FlattenOps.splitBelowOps(belowOps, src,
                alias, Pipelines.navSteps(src.pipeline()).keySet());
        Set<String> allHeads = new LinkedHashSet<>(downstreamHeads);
        allHeads.addAll(bsp.hopTailHeads());
        for (String h : allHeads) {
            TypedSpec hb = t.bindings().get(SyntheticHeads.realHead(h));
            if (hb == null) {
                continue;
            }
            String na = InnerDemand.navSlotAlias(hb, t.rowVar(), tNavSteps.keySet());
            if (na != null) {
                tNavDemand.add(na);
                headNavAlias.put(h, na);
                continue;
            }
            CorrelatedSubselects.collectAliasReads(hb, t.rowVar(), tSlots,
                    tSlotDemand);
        }
        final Set<String> fSlotDemand =
                Pipelines.closeOverConditions(t.pipeline(), tSlotDemand);
        final Set<String> fNavDemand = tNavDemand;
        Pipelines.Materialized[] innerM = new Pipelines.Materialized[1];
        TypedSpec spliced = src.pipeline();
        if (!bsp.spliceOps().isEmpty()) {
            // non-colliding below-ops splice with THE factory's materials
            // (docs/NESTED_SCOPE_REGISTRIES.md — the Map.of() registries
            // walled multi-hop reads here, fe96e380)
            BelowScope bsc = belowScope(src, bsp.spliceOps(), context,
                    src.pipeline());
            spliced = FlattenOps.spliceBelow(bsc.pipeline(),
                    bsp.spliceOps(), bsc.sub());
        }
        Pipelines.Materialized m = Pipelines.materialize(
                spliced, java.util.Set.of(), java.util.Set.of(alias),
                src.classFqn(),
                (a, tc) -> {
                    Pipelines.Materialized im = Pipelines.materialize(
                            sources.get(src.mappingFqn(), tc).pipeline(),
                            tc.equals(targetClass) ? fSlotDemand : java.util.Set.of(),
                            tc.equals(targetClass) ? fNavDemand : java.util.Set.of(),
                            tc,
                            (a2, tc2) -> Pipelines.materialize(
                                    sources.get(src.mappingFqn(), tc2).pipeline(),
                                    java.util.Set.of(), tc2).pipeline());
                    if (tc.equals(targetClass)) {
                        innerM[0] = im;
                    }
                    return im.pipeline();
                });
        String prefix = m.slotPrefixes().get(alias);
        if (prefix == null) {
            throw new IllegalStateException("resolver bug: demanded navigate"
                    + " slot '" + alias + "' produced no prefix");
        }
        Map<String, String> innerPrefixes = innerM[0] == null
                ? Map.of() : innerM[0].slotPrefixes();
        // binding pre-rewrite uses JOINSLOT prefixes only: a nav-HEAD
        // binding is a bare class-typed slot read (provenance AssocSub
        // dispatches it) — the row-read rewriter would throw on it
        Map<String, String> innerSlotOnly = new LinkedHashMap<>(innerPrefixes);
        innerSlotOnly.keySet().removeAll(fNavDemand);
        Map<String, Substitution.SubNav> hopSubNavs = new LinkedHashMap<>();
        for (var he : headNavAlias.entrySet()) {
            String ip = innerPrefixes.get(he.getValue());
            if (ip == null) {
                continue;   // step not materialized: the read stays loud
            }
            var navT = java.util.Objects.requireNonNull(tNavSteps.get(he.getValue())).target();
            if (!(navT instanceof TypedGetAll ng)) {
                continue;
            }
            ClassSource sub = sources.get(src.mappingFqn(), ng.classFqn());
            provOut.put(he.getKey(), new Substitution.AssocSub(
                    prefix + ip, sub.rowVar(), sub.bindings(),
                    sub.classFqn(),
                    Pipelines.slotAliases(sub.pipeline())));
            hopSubNavs.put(he.getKey(), new Substitution.SubNav(
                    ip, sub.rowVar(), sub.bindings()));
        }
        // audit 21b F3 + POSITIONAL rule: value/graph terminals re-stamp
        // the hop's join INNER (a phantom all-null object must not
        // serialize/count); TDS terminals keep materialize's LEFT —
        // the engine's null rows are asserted by the corpus.
        TypedSpec innerized = rowPreserving ? m.pipeline()
                : FlattenOps.innerizeFlattenJoin(m.pipeline(), prefix);
        m = new Pipelines.Materialized(innerized, m.slotPrefixes(),
                m.stripped());
        if (!bsp.hoisted().isEmpty()) {
            // colliding below-ops apply HERE: reads through the hop head
            // dispatch via the hop's AssocSub (prefix + inner prefixes) —
            // row-equivalent to below-application under the INNER hop
            Map<String, Substitution.AssocSub> hopAssocs =
                    new LinkedHashMap<>();
            for (String hh : bsp.hopHeads()) {
                hopAssocs.put(hh, new Substitution.AssocSub(prefix,
                        t.rowVar(), t.bindings(), targetClass,
                        Pipelines.slotAliases(t.pipeline()), innerPrefixes,
                        null, null, temporal.milestoneColumnsOf(
                                t.pipeline(), targetClass), hopSubNavs));
            }
            final Pipelines.Materialized mf = m;
            String hv = CorrelatedSubselects.freshRowVar(src, bsp.hoisted(),
                    m.pipeline(), List.of(), List.of(), Map.of(),
                    () -> freshVarCounter++);
            m = new Pipelines.Materialized(
                    FlattenOps.applyHoisted(m.pipeline(), bsp.hoisted(),
                            fn -> substitution(src, mf, hopAssocs, Set.of(),
                                    Map.of(), Map.of(), Map.of(), true, hv, fn)
                                    .rewriteLambda(fn)),
                    m.slotPrefixes(), m.stripped());
        }
        Type.RelationType row =
                (Type.RelationType) m.pipeline().info().type();
        ExprType rowInfo = new ExprType(row,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        Map<String, TypedSpec> bindings = new LinkedHashMap<>();
        for (var e : t.bindings().entrySet()) {
            // scalar-through-slot bindings flatten onto the materialized
            // target row FIRST (inner slot prefixes), then the hop prefix
            TypedSpec b = e.getValue();
            if (!innerSlotOnly.isEmpty()
                    && !(Pipelines.unwrapToOne(b) instanceof TypedNewInstance)) {
                b = Pipelines.rewriteRowReads(b, t.rowVar(), innerSlotOnly,
                        Set.of(), java.util.function.UnaryOperator.identity());
            }
            bindings.put(e.getKey(), FlattenOps.prefixBinding(b,
                    t.rowVar(), prefix, src.rowVar(), rowInfo));
        }
        return new ClassSource(src.mappingFqn(), targetClass, t.setId(),
                m.pipeline(), src.rowVar(), bindings, row);
    }

    /**
     * The AUTO-MAP FLATTEN's composed source (slice 3): the class-terminal
     * hop {@code Source.all().assocEnd} re-roots the chain at the TARGET
     * class over the JOINED pipeline — source extent &#8904; target pipeline
     * on the association condition (row explosion = projection semantics),
     * target bindings re-pointed through the join prefix. JOIN KIND is
     * POSITIONAL: TDS terminals (project/groupBy) keep the engine's LEFT
     * (unmatched parents ride as null rows — corpus-asserted); value/graph
     * terminals stamp INNER = the engine READER's null-pk skip (21b F3). */
    private ClassSource flattenSource(ClassSource src, String hop,
            Context context, List<TypedSpec> ops,
            @com.legend.Nullable TypedSpec top,
            Set<String> extraHeads,
            Map<String, Substitution.AssocSub> provOut,
            List<TypedSpec> belowOps, boolean rowPreserving) {
        // ROUTE by the hop's MAPPING: a class-typed Join PM is a NAVIGATE
        // SLOT (pipeline carries its TypedNavigate step); an
        // AssociationMapping end routes via the association predicate.
        Set<String> heads = FlattenOps.downstreamHeads(ops, top);
        heads.addAll(extraHeads);
        // MULTI-HOP: a hop the PREVIOUS flatten already materialized (its
        // navigate join rides the composed pipeline; provOut carries the
        // dispatch route) re-roots onto the joined columns — no second join.
        Substitution.AssocSub pre = provOut.remove(hop);
        if (pre != null) {
            return flattenMaterializedNav(src, pre, belowOps, context,
                    rowPreserving);
        }
        TypedSpec hopBinding = src.bindings().get(hop);
        var navSteps = Pipelines.navSteps(src.pipeline());
        String alias = hopBinding == null ? null
                : InnerDemand.navSlotAlias(hopBinding, src.rowVar(), navSteps.keySet());
        if (alias != null) {
            return flattenNavSlot(src, alias, java.util.Objects
                    .requireNonNull(navSteps.get(alias), "navSteps.get(alias)"),
                    heads, provOut, belowOps, context, rowPreserving);
        }
        AssociationJoins.AssocJoin aj = assocMaterial.associationJoin(
                temporal, src, hop, context, false, heads);
        Pipelines.Materialized m = Pipelines.materialize(
                src.pipeline(), java.util.Set.of(), src.classFqn());
        TypedSpec left = m.pipeline();
        if (!belowOps.isEmpty()) {
            // slice 3: this splice ran with Map.of() registries — the
            // below-ops' consumed chains get the factory's materials over
            // the MATERIALIZED left (the assoc route joins above it)
            BelowScope bsc = belowScope(src, belowOps, context, m.pipeline());
            left = FlattenOps.applyBelow(bsc.pipeline(), belowOps, bsc.sub());
        }
        Type.RelationType leftRow =
                (Type.RelationType) left.info().type();
        List<Type.Column> cols = new ArrayList<>(leftRow.columns());
        for (Type.Column c : aj.targetRow().columns()) {
            cols.add(new Type.Column(aj.prefix() + c.name(),
                    c.type(), c.multiplicity()));
        }
        Type.RelationType row = new Type.RelationType(cols);
        ExprType rowInfo = new ExprType(row,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        TypedSpec joined = new TypedJoin(left, aj.targetPipeline(),
                rowPreserving ? leftKind() : innerKind(), java.util.Objects.requireNonNull(aj.condition()),
                Optional.of(aj.prefix()),
                // a VIEW-backed target joins as a frame NAMED BY THE VIEW
                // (legalentity_view_0, never the physical table's group)
                ViewFrames.frameNameOf(ctx, aj.target()), rowInfo,
                false /* resolver-synth */);
        Map<String, TypedSpec> bindings = new LinkedHashMap<>();
        for (var e : aj.target().bindings().entrySet()) {
            // scalar-through-slot bindings flatten onto the MATERIALIZED
            // target row (W4 demandedLeaves), then prefix. Ctor bindings
            // skip: prefixBinding walks their props itself.
            TypedSpec b = e.getValue();
            if (!aj.targetSlotPrefixes().isEmpty()
                    && !(Pipelines.unwrapToOne(b) instanceof TypedNewInstance)) {
                b = Pipelines.rewriteRowReads(b, aj.target().rowVar(),
                        aj.targetSlotPrefixes(), Set.of(),
                        java.util.function.UnaryOperator.identity());
            }
            bindings.put(e.getKey(), FlattenOps.prefixBinding(b,
                    aj.target().rowVar(), aj.prefix(), src.rowVar(), rowInfo));
        }
        return new ClassSource(src.mappingFqn(), aj.target().classFqn(),
                aj.target().setId(), joined, src.rowVar(), bindings, row);
    }

    /** MULTI-HOP flatten re-root (#63 testChainedFiltersGet): the hop's
     * target columns already ride the composed pipeline under the
     * provenance AssocSub's prefix — splice the segment below, stamp the
     * hop's join per the positional kind rule, re-point the bindings. */
    private ClassSource flattenMaterializedNav(ClassSource src,
            Substitution.AssocSub pre, List<TypedSpec> belowOps,
            Context context, boolean rowPreserving) {
        TypedSpec spliced = src.pipeline();
        if (!belowOps.isEmpty()) {
            // slice 3: the re-root splice ran with Map.of() registries —
            // factory materials over the composed pipeline
            BelowScope bsc = belowScope(src, belowOps, context,
                    src.pipeline());
            spliced = FlattenOps.spliceBelow(bsc.pipeline(), belowOps,
                    bsc.sub());
        }
        TypedSpec innerized = rowPreserving ? spliced
                : FlattenOps.innerizeFlattenJoin(spliced, pre.prefix());
        Type.RelationType row =
                (Type.RelationType) innerized.info().type();
        ExprType rowInfo = new ExprType(row,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        Map<String, TypedSpec> bindings = new LinkedHashMap<>();
        for (var e : pre.targetBindings().entrySet()) {
            TypedSpec b = e.getValue();
            if (!pre.targetSlotPrefixes().isEmpty()
                    && !(Pipelines.unwrapToOne(b) instanceof TypedNewInstance)) {
                b = Pipelines.rewriteRowReads(b, pre.targetRowVar(),
                        pre.targetSlotPrefixes(), Set.of(),
                        java.util.function.UnaryOperator.identity());
            }
            bindings.put(e.getKey(), FlattenOps.prefixBinding(b,
                    pre.targetRowVar(), pre.prefix(), src.rowVar(), rowInfo));
        }
        ClassSource t = sources.get(src.mappingFqn(), pre.targetClassFqn());
        return new ClassSource(src.mappingFqn(), pre.targetClassFqn(),
                t.setId(), innerized, src.rowVar(), bindings, row);
    }

    private static Type sourceClassType(TypedSpec chain) {
        Type t = chain.info().type();
        if (!(t instanceof Type.ClassType)) {
            throw new IllegalStateException("resolver bug: object-space chain typed "
                    + t.typeName());
        }
        return t;
    }

    /** The synthetic single-column projection for a scalar map/property
     * read over instances. {@code valueMult} is the ORIGINAL expression's
     * multiplicity — a to-many read is a VALUE COLLECTION and the scalar
     * lowering must LIST-aggregate it (contains/in consumers), while a
     * to-one read stays the bare scalar subquery. The COLLECTION fact
     * rides the relation's ExprType (valueMult); the COLUMN declares the
     * PER-CELL multiplicity — each row holds ONE cell (pair-#4
     * elimination, STAMP_DISCIPLINE_PROGRAM: copying the collection
     * mult onto the column stamped every per-row read many — the C5
     * u_map__active witness and the invariant's one abusable skip). */
    private static TypedProject scalarMapAsProject(TypedSpec source, TypedLambda mapper,
            com.legend.compiler.element.type.Multiplicity valueMult) {
        TypedSpec body = mapper.body().get(mapper.body().size() - 1);
        String name = com.legend.sql.SqlSelect.SYNTH_MAP_COL
                + (body instanceof TypedPropertyAccess bpa
                        ? bpa.property() : "value");
        Type.Param result =
                ((Type.FunctionType) mapper.info().type()).result();
        com.legend.compiler.element.type.Multiplicity cellMult =
                result.multiplicity() instanceof com.legend.compiler.element
                        .type.Multiplicity.Bounded rb
                        && rb.upper() != null && rb.upper() <= 1
                ? result.multiplicity()
                : com.legend.compiler.element.type.Multiplicity.Bounded.ZERO_ONE;
        Type.RelationType row =
                new Type.RelationType(List.of(
                        new Type.Column(name, result.type(), cellMult)));
        return new TypedProject(source,
                List.of(new TypedFuncCol(name, mapper)),
                new ExprType(row, valueMult));
    }

    /** size()/count() over a class extent = the ROW COUNT of the resolved
     * pipeline: project ONE constant column (no slot demand — engine emits
     * select count(*)) and count the relation. */
    private TypedSpec classExtentCount(TypedNativeCall nc, Context context) {

                Type intType =
                        Type.Primitive.INTEGER;
                ExprType oneInt =
                        new ExprType(intType,
                                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
                ExprType rowParam =
                        new ExprType(
                                nc.args().get(0).info().type(),
                                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
                TypedLambda one = new TypedLambda(List.of("p"),
                        List.of(new TypedCInteger(1L, oneInt)),
                        new ExprType(
                                new Type.FunctionType(
                                        List.of(new Type.Param(
                                                rowParam.type(), rowParam.multiplicity())),
                                        new Type.Param(
                                                intType,
                                                com.legend.compiler.element.type.Multiplicity.Bounded.ONE)),
                                com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
                Type.RelationType relType =
                        new Type.RelationType(List.of(
                                new Type.Column(
                                        "c", intType,
                                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE)));
                TypedProject proj = new TypedProject(nc.args().get(0),
                        List.of(new TypedFuncCol("c", one)),
                        new ExprType(relType,
                                com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
                TypedSpec rel = resolveChain(proj, context);
                var relSize = ctx.findFunction("meta::pure::functions::relation::size")
                        .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                                "relation size overload missing from the catalog"));
                return new TypedNativeCall(relSize,
                        List.of(rel), nc.info());
                }

    /** Scalar-space filter over an exploded hop column: the folded source
     * is a ONE-COLUMN relation; the predicate's scalar param becomes a
     * read of that column. */
    private TypedSpec foldScalarHopFilter(TypedFilter f, Context context) {
        TypedSpec rel = resolveNode(f.source(), context);
        if (!(rel.info().type() instanceof Type.RelationType rt)
                || rt.columns().size() != 1) {
            throw new NotImplementedException("scalar filter over a"
                    + " class-derived collection did not fold to a"
                    + " one-column relation");
        }
        Type.RelationType.Column col = rt.columns().get(0);
        TypedSpec rowRead = new TypedPropertyAccess(
                new com.legend.compiler.spec.typed.TypedVariable(
                        "r", ExprType.one(rt)),
                col.name(), new ExprType(col.type(), col.multiplicity()));
        TypedSpec pred = substituteParam(f.predicate(), rowRead);
        TypedLambda fn = new TypedLambda(List.of("r"), List.of(pred),
                f.predicate().info());
        return new TypedFilter(rel, fn, rel.info());
    }

    /** &beta;-substitute a one-param lambda's variable with {@code read} —
     * via the inliner's LET reduction (one substitution engine, no second
     * walker). */
    private TypedSpec substituteParam(TypedLambda lam, TypedSpec read) {
        // audit 21b F6 (named wall): the let-reduction splices `read` at
        // EVERY param read. For a row-rooted read that is plain multi-eval;
        // for a source CHAIN it is DECORRELATION — a second fresh extent
        // replaces the row-correlated value. Refusing here is by design,
        // not an accident of downstream vocabulary walls.
        if (!rowRootedRead(read)) {
            int reads = 0;
            for (TypedSpec b : lam.body()) {
                reads += countParamReads(b, lam.parameters().get(0));
            }
            if (reads > 1) {
                throw new NotImplementedException("class-result mapper reads"
                        + " its parameter " + reads + " times; splicing the"
                        + " source chain at each read would decorrelate the"
                        + " later reads (a fresh extent replaces the"
                        + " row-correlated value)");
            }
            if (reads == 0) {
                // audit 22a L7: a mapper that IGNORES its parameter has
                // per-element semantics in pure (one result per source
                // element); the let-splice collapses that to ONE evaluation
                // — wrong cardinality, silently.
                throw new NotImplementedException("class-result mapper"
                        + " ignores its parameter — the splice would collapse"
                        + " per-element semantics to one evaluation");
            }
        }
        java.util.List<TypedSpec> body = new java.util.ArrayList<>();
        body.add(new com.legend.compiler.spec.typed.TypedLet(
                lam.parameters().get(0), read, read.info()));
        body.addAll(lam.body());
        return new com.legend.compiler.spec.UserCallInliner(specs)
                .inlineBody(body).get(0);
    }

    /** A read rooted at a VARIABLE (row-correlated): duplication is
     * multi-eval of the same row's value, never decorrelation. */
    private static boolean rowRootedRead(TypedSpec read) {
        return switch (read) {
            case TypedVariable ignored -> true;
            case TypedPropertyAccess pa -> rowRootedRead(pa.source());
            default -> false;
        };
    }

    /** Shadow-aware count of {@code $var} reads beneath {@code n}. */
    private static int countParamReads(TypedSpec n, String var) {
        if (n instanceof TypedVariable v) {
            return v.name().equals(var) ? 1 : 0;
        }
        if (n instanceof TypedLambda l && l.parameters().contains(var)) {
            return 0;
        }
        int c = 0;
        for (TypedSpec ch : n.children()) {
            c += countParamReads(ch, var);
        }
        return c;
    }

    /** The node is (part of) an object-space chain — see {@link Anchors#spaceOf}. */
    private boolean objectSpace(TypedSpec s) {
        return anchors.spaceOf(s) == Space.OBJECT;
    }

    /** {@code at(coll, k)} with a LITERAL index — class-space slice. */
    static boolean isStaticAt(TypedNativeCall c) {
        return c.args().size() == 2
                && "meta::pure::functions::collection::at"
                        .equals(c.callee().qualifiedName())
                && c.args().get(1)
                        instanceof TypedCInteger;
    }

    /** {@code toOne(instances)}: multiplicity coercion over a class
     * collection — PASS-THROUGH in the pipeline (the engine raises on
     * N&ne;1; here the value compare sees all N and fails loud — a
     * documented, weaker-but-never-silent stand-in). */
    static boolean isClassToOne(TypedNativeCall c) {
        return c.args().size() == 1
                && "meta::pure::functions::multiplicity::toOne"
                        .equals(c.callee().qualifiedName());
    }

    private static final String FIRST_FQN = "meta::pure::functions::collection::first";
    private static final String HEAD_FQN = "meta::pure::functions::collection::head";
    private static final String SORT_FQN = "meta::pure::functions::collection::sort";
    private static final String SORT_BY_FQN = "meta::pure::functions::collection::sortBy";
    private static final String SORT_BY_REV_FQN = "meta::pure::functions::collection::sortByReversed";
    private static final String CONCAT_FQN =
            "meta::pure::functions::collection::concatenate";
    private static final String COMPARE_FQN = "meta::pure::functions::lang::compare";
    private static final String EQUAL_FQN = "meta::pure::functions::boolean::equal";
    private static final String EQ_FQN = "meta::pure::functions::boolean::eq";

    /** first()/head() over an object-space chain — LIMIT 1 in disguise. */
    static boolean isFirstLike(TypedNativeCall c) {
        String fqn = c.callee().qualifiedName();
        return c.args().size() == 1
                && (FIRST_FQN.equals(fqn) || HEAD_FQN.equals(fqn));
    }

    /**
     * Class-space {@code sort(key, {x,y|compare})}: the comparator must be a
     * BARE compare over the two parameters — its argument order IS the
     * direction ({@code $x->compare($y)} ascending, {@code $y->compare($x)}
     * descending). Anything richer has no relation sort shape.
     */
    static @com.legend.Nullable TypedSortBy classSortOf(TypedSpec n) {
        // class-space sortBy(coll, key)/sortByReversed — the 2-arg native
        // spelling of the relation sort (computed keys substitute like any)
        if (n instanceof TypedNativeCall sb && sb.args().size() == 2
                && (SORT_BY_FQN.equals(sb.callee().qualifiedName())
                        || SORT_BY_REV_FQN.equals(sb.callee().qualifiedName()))
                && sb.args().get(1) instanceof TypedLambda key2) {
            return new TypedSortBy(sb.args().get(0), key2,
                    SORT_BY_FQN.equals(sb.callee().qualifiedName()),
                    sb.info());
        }
        if (!(n instanceof TypedNativeCall c) || c.args().size() != 3
                || !SORT_FQN.equals(c.callee().qualifiedName())
                || !(c.args().get(1) instanceof TypedLambda key)
                || !(c.args().get(2) instanceof TypedLambda cmp)) {
            return null;
        }
        Boolean ascending = comparatorDirection(cmp);
        return ascending == null ? null
                : new TypedSortBy(c.args().get(0), key, ascending, c.info());
    }

    private static @com.legend.Nullable Boolean comparatorDirection(TypedLambda cmp) {
        if (cmp.parameters().size() != 2 || cmp.body().size() != 1
                || !(cmp.body().get(0) instanceof TypedNativeCall cc)
                || !COMPARE_FQN.equals(cc.callee().qualifiedName())
                || cc.args().size() != 2
                || !(cc.args().get(0) instanceof TypedVariable a)
                || !(cc.args().get(1) instanceof TypedVariable b)) {
            return null;
        }
        String p0 = cmp.parameters().get(0);
        String p1 = cmp.parameters().get(1);
        if (a.name().equals(p0) && b.name().equals(p1)) {
            return Boolean.TRUE;
        }
        if (a.name().equals(p1) && b.name().equals(p0)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** concatenate over two class-collection chains, both fetch-bearing. */
    private @com.legend.Nullable TypedNativeCall classConcatOf(TypedSpec n) {
        return n instanceof TypedNativeCall c && c.args().size() == 2
                && CONCAT_FQN.equals(c.callee().qualifiedName())
                && anchored(c.args().get(0)) && anchored(c.args().get(1))
                ? c : null;
    }

    /** Statically decide an if() condition, or null when genuinely runtime. */

    /** Anchor reachability, memoized per pass — see {@link Anchors}. */
    private final Anchors anchors = new Anchors();

    private boolean anchored(TypedSpec n) {
        return anchors.anchored(n);
    }

    static boolean containsGetAll(TypedSpec n) {
        return Anchors.containsGetAll(n);
    }

    // =====================================================================
    // Object-space chain resolution (the H2 heart)
    // =====================================================================

    private TypedSpec resolveChain(TypedSpec top, Context context) {
        if (context.isNone()) {
            throw new MappingResolutionException(
                    "class query requires an execution context: add"
                            + " ->from(mapping, runtime) or supply a runtime");
        }
        // EVERY chain resolution (nested ones via ANY recursion path)
        // restores the caller's temporal frame on exit (T1.3 class kill)
        TemporalFrame caller = temporal;
        try {
            return resolveObject(top, context);
        } finally {
            temporal = caller;
        }
    }

    /**
     * Resolve one object-space chain (SCAN-THEN-MATERIALIZE, plan §2.2):
     * collect the ops, scan the user lambdas for demand, materialize the
     * pipeline (demanded slots -> prefixed LEFT joins; un-demanded slots
     * CANCELLED), THEN fold the ops back on with substitution against the
     * final row type. No restamp pass exists.
     */

    /** PHASE output: navigate-slot registration — the demanded slots and
     * nav steps, their materialized targets (NavMaterializer.NavMat trees), the per-head
     * substitution material, and the SECOND-identity extras routed to the
     * association fold (per-use join identity). */
    private record NavPlan(Set<String> demanded, Set<String> demandedNavs,
            Map<String, Substitution.AssocSub> assocs,
            Map<String, NavMaterializer.NavMat> navMats,
            Map<String, List<List<String>>> navTails,
            Map<String, String> navHeadByAlias,
            Map<String, String> extraNavHeads,
            Map<String, List<List<String>>> extraNavTails,
            Map<String, TypedNavigate> navSteps,
            Map<String, String> corrNavHeads) {}

    /** PHASE — slot + navigate-step demand: heads whose bindings read
     * join slots demand them; class-typed Join PM heads materialize their
     * targets (recursively, with per-hop temporal context) and register
     * the head substitution material under the chain key. */
    /** Filter/sortBy op-lambda demand: filter predicates feed filterPaths
     * (membership scan + corrPred outer demand), sortBy keys feed the
     * projection-side agg scan — the ENTRY RULE holds (scans enter through
     * lambda BODIES, never the lambda node). */
    private void collectOpDemand(List<TypedSpec> ops, ClassSource cs,
            Set<List<String>> filterPaths, Set<List<String>> projectionPaths,
            Map<String, List<AggDemand>> aggDemands) {
        for (TypedSpec op : ops) {
            if (op instanceof TypedFilter f) {
                for (TypedSpec b : f.predicate().body()) {
                    memberScan(b, f.predicate().parameters().get(0), cs, filterPaths);
                }
                // #69: outer reads in corrPreds may root at THIS filter's
                // param too (the RhsFilter family) — same parent demand
                // as the terminal-lambda scan
                synthetics.corrPredOuterDemand(f.predicate(), filterPaths);
            }
            if (op instanceof TypedSortBy sb) {
                for (TypedSpec b : sb.key().body()) {
                    CorrelatedSubselects.aggScan(b, sb.key().parameters().get(0), cs,
                            aggDemands, projectionPaths,
                            this::isToManyAssocHead, this::isAssocOrNavHead);
                }
            }
        }
    }

    /** OTHERWISE per-leaf dispatch (V1 §D.5): an embedded-partial leaf
     * reads the PARENT row — {@code null} means no join demand (the
     * caller skips the path). KIND-aware (ledger cluster 50): membership
     * in the partial proves same-row ONLY for a genuine column read; a
     * CLASS-TYPED navigate-slot member (structural Join sub-PM) returns
     * the PARTIAL so the ctor drill descends and registers the dotted
     * AssocSub (the partial's own mapping wins over the otherwise
     * target's); any other leaf demands the FALLBACK's navigate slot. */
    private static @com.legend.Nullable TypedSpec otherwiseNavRead(
            TypedSpec headBinding, List<String> path, ClassSource cs,
            Set<String> navStepKeys) {
        var ow = Substitution.otherwiseOf(headBinding);
        if (ow == null) {
            return headBinding;
        }
        var partial = (TypedNewInstance) ow.args().get(0);
        TypedSpec pb = partial.properties().get(
                SyntheticHeads.realHead(path.get(1)));
        if (pb == null) {
            return ow.args().get(1);
        }
        return InnerDemand.navSlotAlias(pb, cs.rowVar(), navStepKeys) == null
                ? null : partial;
    }

    private NavPlan registerNavigations(ClassSource cs,
            Set<List<String>> paths, Set<String> splitChains) {
        // Slot demand (heads whose bindings read join slots).
        Set<String> slotAliases = Pipelines.slotAliases(cs.pipeline());
        Set<String> demanded = new LinkedHashSet<>();
        if (!slotAliases.isEmpty()) {
            for (List<String> path : paths) {
                TypedSpec binding = cs.bindings().get(SyntheticHeads.realHead(path.get(0)));
                if (binding != null) {
                    CorrelatedSubselects.collectAliasReads(binding, cs.rowVar(), slotAliases, demanded);
                }
            }
        }

        // Navigate-step (class-typed Join PM) demand: a 2-hop path whose
        // head binding reads a navigate slot ($row.alias, class-typed)
        // demands that step; its target joins as the class's own pipeline.
        var navSteps = Pipelines.navSteps(cs.pipeline());
        Set<String> demandedNavs = new LinkedHashSet<>();
        Map<String, Substitution.AssocSub> assocs = new LinkedHashMap<>();
        Map<String, List<List<String>>> navTails =
                new LinkedHashMap<>();
        Map<String, String> navHeadByAlias = new LinkedHashMap<>();
        // H5c: heads whose binding is a class-typed M2M cast — the
        // AssocSub's bindings swap to the cast target's composed source
        Map<String, String> castHeads = new LinkedHashMap<>();
        // SECOND identities on one physical slot (date-fingerprinted /
        // filter-lifted synthetic heads beside the base): the slot
        // materializes once for the FIRST identity; every other identity
        // emits its OWN prefixed join from the same nav material (engine:
        // joins keyed by date / per-use). headKey → slot alias, + tails.
        Map<String, String> extraNavHeads = new LinkedHashMap<>();
        Map<String, List<List<String>>> extraNavTails =
                new LinkedHashMap<>();
        Map<String, String> corrNavHeads = new LinkedHashMap<>();
        // slot claims resolve PLAIN-first (stable): the un-fingerprinted
        // identity keeps the physical slot (its copy columns spell
        // alias_leaf); fingerprinted identities take the extra prefixed
        // route — a renamed head claiming the slot would force the plain
        // identity into a prefix colliding with the slot's own columns
        List<List<String>> orderedPaths = new ArrayList<>(paths);
        orderedPaths.sort(java.util.Comparator.comparingInt(
                pp -> pp.get(0).contains("#d") ? 1 : 0));
        for (List<String> path : orderedPaths) {
            if (path.size() < 2) {
                continue;
            }
            TypedSpec headBinding = cs.bindings().get(SyntheticHeads.realHead(path.get(0)));
            if (headBinding == null) {
                continue;   // association heads (below)
            }
            TypedSpec navRead = otherwiseNavRead(headBinding, path, cs,
                    navSteps.keySet());
            if (navRead == null) {
                continue;   // embedded leaf: parent-alias read, no join
            }
            // EMBEDDED head: the path walks INTO the ^Inner ctor — the
            // navigate-slot demand comes from the ctor's MID property expr
            // ($p.classification.system.name where classification is
            // embedded and system a class-typed Join sub-PM hoisted onto
            // the owner pipeline); the AssocSub registers under the DOTTED
            // key, which rewritePath's chain lookup already consumes.
            int mid = 1;
            TypedSpec drill = navRead;
            while (true) {
                TypedSpec inner = drill;
                if (inner instanceof TypedNativeCall tc1 && tc1.args().size() == 1
                        && tc1.callee().qualifiedName().equals(
                                "meta::pure::functions::multiplicity::toOne")) {
                    inner = tc1.args().get(0);
                }
                if (inner instanceof TypedNewInstance ni
                        && mid + 1 < path.size()
                        && ni.properties().containsKey(
                                SyntheticHeads.realHead(path.get(mid)))) {
                    // a SYNTHETIC mid component drills by its REAL property;
                    // the parked pred applies at slot materialization (#70)
                    drill = ni.properties().get(
                            SyntheticHeads.realHead(path.get(mid)));
                    mid++;
                } else {
                    drill = inner;
                    break;
                }
            }
            // H5c cast-nav (CastNav): a class-typed M2M binding
            // (^Target($src.slot)) demands the UPSTREAM navigate slot
            String castFqn = CastNav.castTarget(cs, drill);
            if (castFqn != null) {
                drill = CastNav.castSource(drill);
            }
            String alias = InnerDemand.navSlotAlias(drill, cs.rowVar(), navSteps.keySet());
            if (alias == null) {
                continue;
            }
            String headKey = String.join(".", path.subList(0, mid));
            if (castFqn != null) {
                castHeads.putIfAbsent(headKey, castFqn);
            }
            // a lifted head's predicate reads are TAILS too: they pull the
            // target's own slots exactly like demanded leaves
            List<List<String>> predTails =
                    synthetics.predTailsFor(path, mid);
            // JOIN IDENTITY: a SECOND head identity on one physical slot
            // routes through its own prefixed join (below) — the slot
            // itself materializes once for the first identity.
            String priorHead = navHeadByAlias.get(alias);
            if (priorHead != null && !priorHead.equals(headKey)) {
                extraNavHeads.putIfAbsent(headKey, alias);
                List<List<String>> et = extraNavTails
                        .computeIfAbsent(headKey, k -> new ArrayList<>());
                et.add(path.subList(mid, path.size()));
                et.addAll(predTails);
                continue;
            }
            // the head's TAIL paths drive the target's OWN slot demand
            // (nested navigation: $a.b.c.pk materializes b's target WITH
            // its c slot; the leaf reads the composed prefix b_c_pk)
            navTails.computeIfAbsent(alias, k -> new ArrayList<>())
                    .add(path.subList(mid, path.size()));
            navTails.get(alias).addAll(predTails);
            navHeadByAlias.put(alias, headKey);
            // #69 EXPLODING parent-copy reroute: a correlated pred whose
            // OUTER reads hop a parent NAV can never compose on the flat
            // navigate step's ON — the head leaves the slot spine (the
            // slot stays unmaterialized) and joins as an AssocJoin whose
            // target is the parent-copy subselect (fold 2b).
            if (assocMaterial.explodingReroutePred(path, mid) != null
                    && !demandedNavs.contains(alias)) {
                corrNavHeads.putIfAbsent(headKey, alias);
                continue;
            }
            if (mid > 1 && path.subList(0, mid).stream()
                    .anyMatch(c -> synthetics.correlatedPred(c) != null)) {
                // a CORRELATED pred on an embedded-drilled chain component
                // has no composition route yet — leave the step undemanded
                // (loud read), never an unfiltered join
                continue;
            }
            if (demandedNavs.contains(alias)) {
                continue;
            }
            demandedNavs.add(alias);
            var nav = java.util.Objects.requireNonNull(
                    navSteps.get(alias));
            // The nav condition may read joinslot sub-rows: demand them too.
            for (TypedSpec b : nav.predicate().body()) {
                for (String slot : slotAliases) {
                    if (Pipelines.referencesAliasOn(b,
                            nav.predicate().parameters().get(0), Set.of(slot))) {
                        demanded.add(slot);
                    }
                }
            }
        }
        demanded = Pipelines.closeOverConditions(cs.pipeline(), demanded);
        // Materialize each demanded navigate TARGET with the slot demand its
        // tail paths imply (recursively — a tail through the target's own
        // class-typed slot materializes THAT slot's target too), then
        // register the head's substitution material with the REAL slot
        // prefixes (audit: Map.of() here walled every nested slot read).
        Map<String, NavMaterializer.NavMat> navMats = new LinkedHashMap<>();
        for (String alias : demandedNavs) {
            var nav = java.util.Objects.requireNonNull(
                    navSteps.get(alias));
            String targetClass = ((TypedGetAll)
                    nav.target()).classFqn();
            // the lifted head's parked preds thread as parkedPreds: their
            // DIRECT slot-alias reads join the demand (the sub-route's
            // rule, applied to the TOP materialization too — the Fork
            // concat-branch preds read the target's address slot)
            String bareKey0 = navHeadByAlias.getOrDefault(alias, alias);
            bareKey0 = bareKey0.substring(bareKey0.lastIndexOf('.') + 1);
            navMats.put(alias, navMaterializer.navTargetMaterialized(temporal, cs.mappingFqn(), targetClass,
                    navTails.getOrDefault(alias, List.of()),
                    navHeadByAlias.getOrDefault(alias, alias),
                    TemporalContext.NONE,
                    synthetics.allPreds(bareKey0), splitChains));
            // a LIFTED head's predicate applies INSIDE the join target
            // (engine: the chain filter parks on the navigation's join-tree
            // node); the composite right side carries its own filters, so
            // the outer join-stamping never double-stamps it
            String liftedHead = navHeadByAlias.getOrDefault(alias, alias);
            // preds park under the BARE synthetic head — a DOTTED chain
            // key (money.usdRates#f0) keys by its LAST component
            // (silent-skip = unfiltered join = wrong rows)
            String predKey = liftedHead.substring(
                    liftedHead.lastIndexOf('.') + 1);
            if (synthetics.hasPred(predKey)
                    && synthetics.correlatedPred(predKey) == null) {
                ClassSource target = sources.getForNav(cs.mappingFqn(),
                        targetClass, navHeadByAlias.getOrDefault(alias, alias));
                var mat = java.util.Objects.requireNonNull(
                        navMats.get(alias));
                navMats.put(alias, new NavMaterializer.NavMat(
                        synthetics.applyToPipe(predKey, mat.pipeline(),
                                (p, pred) -> CorrelatedSubselects.predFilteredPipe(p, target,
                                        mat.slotPrefixes(), mat.subNavs(),
                                        pred, cs.mappingFqn())),
                        mat.slotPrefixes(), mat.stripped(), mat.subNavs()));
            }
        }
        for (String alias : demandedNavs) {
            var nav = java.util.Objects.requireNonNull(
                    navSteps.get(alias));
            String targetClass = ((TypedGetAll)
                    nav.target()).classFqn();
            ClassSource target = sources.getForNav(cs.mappingFqn(),
                    targetClass, navHeadByAlias.getOrDefault(alias, alias));
            // SUB-navigation material: for each 3-hop tail, the mid
            // property's minted sub-alias, its materialized prefix, and the
            // SUB-TARGET's binding table (leaves resolve through it —
            // audit 12 F1). Un-materialized sub-steps (temporal/filtered
            // gates) are absent: their reads stay loud.
            Map<String, Substitution.SubNav> subNavs =
                    java.util.Objects.requireNonNull(navMats.get(alias)).subNavs();
            // H5c cast head: leaves resolve through the CAST TARGET's
            // composed (M2M) bindings — the same frame (rowVar identity
            // guarded, the graph channel's m2mAssocChild rule); a raw
            // source-column read would silently take the wrong column
            // whenever the M2M binding renames
            String headKey9 = navHeadByAlias.getOrDefault(alias, alias);
            ClassSource leafSource = CastNav.leafSource(sources, cs,
                    castHeads.get(headKey9), target, headKey9);
            assocs.put(headKey9,
                    new Substitution.AssocSub(alias + "_",
                    leafSource.rowVar(), leafSource.bindings(),
                    leafSource.classFqn(),
                    Pipelines.slotAliases(target.pipeline()),
                    navMats.get(alias).slotPrefixes(), null, null,
                    temporal.milestoneColumnsOf(target.pipeline(), target.classFqn()),
                    subNavs, containsFilter(target.pipeline())));
        }

        return new NavPlan(demanded, demandedNavs, assocs, navMats, navTails,
                navHeadByAlias, extraNavHeads, extraNavTails, navSteps,
                corrNavHeads);
    }

    /** #70 composite chain-backed exists/scalar target: the pipeline with
     * the sibling slot's table joined IN, and hop-1's condition oriented
     * onto the composite row. Null when the shape does not apply. */
            /** True when the expression reads {@code var} other than through the
     * {@code slot} sub-row ({@code $var.slot.x}). */
        /** PHASE 2a'' — CLASS-TYPED LEAF under an emptiness call:
     * registers the DOTTED-path correlated-EXISTS material (engine:
     * semi-join + key null check on the exploded chain row); details in
     * the body comments. Mutates {@code existsSubs}. */
    private void registerDottedExistsSubs(ClassSource cs,
            List<TypedSpec> ops, TypedSpec top,
            @com.legend.Nullable List<TypedGraphTree> tree, Context context,
            Map<String, Substitution.AssocSub> assocs,
            Map<String, Substitution.ExistsSub> existsSubs) {
        // 2a''. CLASS-TYPED LEAF under an emptiness call — isNotEmpty(
        // $p.a.b) where b is itself a navigation step on the CHAIN TARGET:
        // correlated EXISTS on the exploded chain row (engine: semi-join +
        // key null check — row-per-row identical truth value). The leaf's
        // nav material registers under the DOTTED path; the oriented
        // condition's parent-side reads PRE-PREFIX onto the chain's joined
        // columns so the exists rewrite's plain var swap lands them on the
        // row. Registered after join-key collection: these conditions read
        // CHAIN columns, never root keys. ONLY paths actually under an
        // emptiness call register — eager registration stamped temporal
        // context on chains the ordinary (inheritance-aware) route owns
        // (regressed testBiTemporalToBiTemporalDatePropagation).
        Set<List<String>> emptinessChainPaths =
                new LinkedHashSet<>();
        Set<List<String>> nestedEmptinessPaths =
                new LinkedHashSet<>();
        for (TypedSpec op : ops) {
            if (op instanceof TypedFilter f) {
                for (TypedSpec b : f.predicate().body()) {
                    InnerDemand.collectEmptinessChainPaths(b,
                            f.predicate().parameters().get(0),
                            emptinessChainPaths, nestedEmptinessPaths);
                }
            }
            if (op instanceof TypedSortBy sb) {
                for (TypedSpec b : sb.key().body()) {
                    InnerDemand.collectEmptinessChainPaths(b, sb.key().parameters().get(0),
                            emptinessChainPaths, nestedEmptinessPaths);
                }
            }
        }
        if (tree == null) {
            for (TypedLambda fn : terminalLambdas(top)) {
                for (TypedSpec b : fn.body()) {
                    InnerDemand.collectEmptinessChainPaths(b, fn.parameters().get(0),
                            emptinessChainPaths, nestedEmptinessPaths);
                }
            }
        }
        Set<List<String>> allEmptinessPaths =
                new LinkedHashSet<>(emptinessChainPaths);
        allEmptinessPaths.addAll(nestedEmptinessPaths);
        for (List<String> path : allEmptinessPaths) {
            if (path.size() < 2) {
                continue;
            }
            String dotted = String.join(".", path);
            Substitution.AssocSub chain = assocs.get(
                    String.join(".", path.subList(0, path.size() - 1)));
            if (existsSubs.containsKey(dotted)) {
                continue;
            }
            // NESTED-EXISTS rung: an emptiness consumed INSIDE another
            // exists' predicate lives in the enclosing SUBSELECT's scope —
            // a to-many mid hop there must NOT correlate through the flat
            // outer join (that form is per-FANNED-row, the engine's truth
            // for DIRECT filter-position emptiness — testIsEmptyNested's
            // golden — but wrong inside a subselect where the fanned row
            // is not the evaluation row); the EXISTS material carries the
            // whole exploded chain itself (ChainedExists; engine: every
            // hop's join-tree node lives inside the exists subselect).
            boolean midToMany = path.size() == 2
                    && !(ctx.findProperty(cs.classFqn(),
                            SyntheticHeads.realHead(path.get(0)))
                            .map(pr -> pr.multiplicity())
                            .filter(mm -> mm instanceof com.legend.compiler
                                    .element.type.Multiplicity.Bounded bb
                                    && Integer.valueOf(1).equals(bb.upper()))
                            .isPresent());
            if (midToMany && nestedEmptinessPaths.contains(path)
                    && !emptinessChainPaths.contains(path)
                    && !synthetics.hasPred(path.get(0))
                    && !synthetics.hasPred(dotted)) {
                Substitution.ExistsSub two = ChainedExists.explodedTwoHop(
                        sources, temporal, cs, path, ops,
                        (t2, pipe2) -> nestedScope(t2, ops, path,
                                context, pipe2));
                if (two != null) {
                    existsSubs.put(dotted, two);
                    continue;
                }
            }
            if (chain == null) {
                continue;
            }
            String leafName = path.get(path.size() - 1);
            String leaf = SyntheticHeads.realHead(leafName);
            ClassSource parent = sources.get(cs.mappingFqn(),
                    chain.targetClassFqn());
            TypedLambda cond;
            TypedSpec tPipe;
            ClassSource t;
            Map<String, String> tPrefixes;
            TypedSpec leafBinding = parent.bindings().get(leaf);
            if (leafBinding != null) {
                TypedSpec inner = leafBinding;
                if (inner instanceof TypedNativeCall c1 && c1.args().size() == 1
                        && c1.callee().qualifiedName().equals(
                                "meta::pure::functions::multiplicity::toOne")) {
                    inner = c1.args().get(0);
                }
                var pNavSteps = Pipelines.navSteps(parent.pipeline());
                String alias = InnerDemand.navSlotAlias(inner, parent.rowVar(),
                        pNavSteps.keySet());
                var nav = alias == null ? null : pNavSteps.get(alias);
                if (nav == null || !(nav.target()
                        instanceof TypedGetAll tg)
                        || !sources.binds(cs.mappingFqn(), tg.classFqn())) {
                    continue;
                }
                t = sources.get(cs.mappingFqn(), tg.classFqn());
                Pipelines.Materialized tm = Pipelines.materialize(
                        t.pipeline(), Set.of(), t.classFqn());
                TypedSpec p0 = tm.pipeline();
                cond = nav.predicate();
                if (cond.parameters().size() == 2) {
                    Set<String> tgtReads = new LinkedHashSet<>();
                    for (TypedSpec b0 : cond.body()) {
                        Pipelines.collectVarReads(b0,
                                cond.parameters().get(1), tgtReads);
                    }
                    p0 = Pipelines.widenConcatenateForKeys(p0, tgtReads);
                }
                tPipe = temporal.temporalTargetPipe(parent, t, dotted,
                        temporal.applyJoinTemporalFilters(p0, t, Map.of()));
                tPrefixes = tm.slotPrefixes();
                final TypedSpec tp = tPipe;
                final var tpx = tPrefixes;
                final ClassSource tt = t;
                requireNoCorrelatedPred(leafName, "exists-navigation");
                tPipe = synthetics.applyToPipe(leafName, tp, (p, pred) ->
                        CorrelatedSubselects.predFilteredPipe(p, tt, tpx, pred, cs.mappingFqn()));
            } else if (ctx.findAssociationOf(parent.classFqn(), leaf)
                    .isPresent()) {
                // the nested predicate's path HEADS demand target slots —
                // without them, a slot-backed read inside the exists
                // predicate ($e.address.name) finds an unmaterialized slot
                Set<String> innerDemand = new LinkedHashSet<>();
                for (TypedLambda il : InnerDemand.lambdas(ops, path)) {
                    if (!il.parameters().isEmpty()) {
                        InnerDemand.collectParamPathHeads(il, il.parameters().get(0),
                                innerDemand);
                    }
                }
                AssociationJoins.AssocJoin aj = assocMaterial.associationJoin(temporal, parent, leafName, context,
                        true, innerDemand, dotted);
                t = aj.target();
                cond = aj.condition();
                tPipe = aj.targetPipeline();
                tPrefixes = aj.targetSlotPrefixes();
            } else {
                continue;
            }
            // audit 23 #75: a MISSING property must not silently default
            // to to-many semantics — the demand scan and G disagree
            if (ctx.findProperty(parent.classFqn(), leaf).isEmpty()) {
                throw new IllegalStateException("resolver bug: exists-leaf"
                        + " property '" + leaf + "' is not declared on '"
                        + parent.classFqn() + "' — G admitted a read the"
                        + " model does not carry");
            }
            boolean leafToMany = !(ctx.findProperty(parent.classFqn(), leaf)
                    .map(pr -> pr.multiplicity())
                    .filter(mm -> mm instanceof com.legend.compiler.element.type
                            .Multiplicity.Bounded bb
                            && Integer.valueOf(1).equals(bb.upper()))
                    .isPresent());
            if (chain.readVar() != null) {
                throw new IllegalStateException("resolver bug: dotted-path"
                        + " EXISTS registration over a read-var AssocSub —"
                        + " the chain prefix pre-prefixing assumes joined-row"
                        + " reads (audit 14 B-F7)");
            }
            String pv = java.util.Objects.requireNonNull(cond, "cond").parameters().get(0);
            TypedSpec cbody = Pipelines.prefixColumns(
                    cond.body().get(cond.body().size() - 1), pv,
                    chain.prefix(), v -> v);
            TypedLambda chainedCond = new TypedLambda(cond.parameters(),
                    List.of(cbody), cond.info());
            NestedScope dns = nestedScope(t, ops, path, context, tPipe);
            existsSubs.put(dotted, new Substitution.ExistsSub(dns.pipeline(),
                    chainedCond, t.rowVar(), t.bindings(),
                    dns.row(),
                    t.classFqn(), Pipelines.slotAliases(t.pipeline()),
                    tPrefixes, leafToMany)
                    .withInnerRegs(dns.regs()));
        }

    }

    /** PHASE 2b-i output: the root pipeline materialized (demanded
     * slots -> prefixed LEFT joins, un-demanded slots CANCELLED), every
     * milestoned join alias stamped by the ambient context, and the root
     * fetch's own milestoning filter applied. */
    private record RootPipe(Pipelines.Materialized m,
            TypedSpec materializedPipe) {}

    /** PHASE 2b-i — materialize the root pipeline and stamp it: slot
     * demand -> Pipelines.materialize; the join-walk applies the ambient
     * temporal context per alias (class-governed nav targets, chained-PM
     * mid tables by their OWN milestoning, physical slots); then the
     * fetch's own point/pair/range filter. */
    private RootPipe materializeRoot(ClassSource cs, TypedGetAll g,
            Set<String> demanded, Set<String> demandedNavs,
            Map<String, NavMaterializer.NavMat> navMats, Map<String, String> navHeadByAlias,
            Map<String, Substitution.AssocSub> parentAssocs,
            Set<String> dateAliases) {
        Set<String> corrComposed = new LinkedHashSet<>();
        TypedSpec csPipe = augmentNavPredicates(
                Pipelines.sinkNavSteps(cs.pipeline(), dateAliases), cs,
                navHeadByAlias, demandedNavs, corrComposed, parentAssocs,
                navMats);
        // audit 21b F1 backstop: a demanded navigate head carrying a
        // correlated predicate that the augment walk did NOT compose has
        // exactly one fate — loud. The lifted-pred apply site skips
        // correlated preds on the assumption they were composed here;
        // proceeding would silently drop the correlation (wrong rows).
        for (String alias : demandedNavs) {
            String head = navHeadByAlias.getOrDefault(alias, alias);
            if (synthetics.correlatedPred(head) != null
                    && !corrComposed.contains(alias)) {
                throw new IllegalStateException("resolver bug: correlated"
                        + " predicate on navigate step '" + alias + "' (head '"
                        + SyntheticHeads.realHead(head) + "') was not composed"
                        + " into the join condition — the augment walk missed"
                        + " the step (spine node unhandled?); proceeding would"
                        + " silently drop the correlation");
            }
        }
        Pipelines.Materialized m = Pipelines.materialize(
                csPipe, demanded, demandedNavs, cs.classFqn(),
                (alias, targetClass) -> navMats.containsKey(alias)
                        ? navMats.get(alias).pipeline()
                        : Pipelines.materialize(
                                sources.get(cs.mappingFqn(), targetClass).pipeline(),
                                Set.of(), targetClass).pipeline());
        Map<String, String> navPrefixToClass = new LinkedHashMap<>();
        Map<String, String> navPrefixToChain = new LinkedHashMap<>();
        Map<String, String> midPrefixToChain = new LinkedHashMap<>();
        Map<String, com.legend.compiler.element.MilestoningStrategy> midPrefixToDim = new LinkedHashMap<>();
        Set<String> slotAliases = Pipelines.slotAliases(cs.pipeline());
        for (var navE : Pipelines.navSteps(cs.pipeline()).entrySet()) {
            if (navE.getValue().target()
                    instanceof TypedGetAll tg2) {
                String chain = navHeadByAlias.getOrDefault(navE.getKey(),
                        navE.getKey());
                navPrefixToClass.put(navE.getKey() + "_", tg2.classFqn());
                navPrefixToChain.put(navE.getKey() + "_", chain);
                // MID slots of a CHAINED PM (@J1 > @J2): the nav condition
                // reads their sub-rows — a milestoned mid table filters by
                // its OWN milestoning against the CHAIN's context (engine
                // applyMilestoningFilters stamps every milestoned join-tree
                // node with the ambient date; the TARGET class's temporality
                // never governs the mid table — audit 14 F1: keying mid
                // slots by target class left them unstamped for
                // non-temporal targets). Two chains claiming one slot with
                // DIFFERENT specs is loud — first-writer-wins would stamp
                // the second chain's rows with the wrong date.
                for (TypedSpec b : navE.getValue().predicate().body()) {
                    for (String slot : slotAliases) {
                        if (Pipelines.referencesAliasOn(b,
                                navE.getValue().predicate().parameters().get(0),
                                Set.of(slot))) {
                            String priorChain = midPrefixToChain
                                    .putIfAbsent(slot + "_", chain);
                            if (priorChain != null && !priorChain.equals(chain)
                                    && !Objects.equals(
                                            temporal.spec(priorChain),
                                            temporal.spec(chain))) {
                                throw new NotImplementedException(
                                        "physical slot '" + slot + "' is shared"
                                        + " by chains '" + priorChain + "' and '"
                                        + chain + "' carrying different"
                                        + " milestoning dates — per-chain mid"
                                        + " joins are not supported yet");
                            }
                            midPrefixToDim.putIfAbsent(slot + "_",
                                    temporal.temporalStrategy(tg2.classFqn()));
                        }
                    }
                }
            }
        }
        final TypedSpec basePipe =
                temporal.applyJoinTemporalFilters(m.pipeline(), cs, navPrefixToClass,
                        navPrefixToChain, midPrefixToChain, midPrefixToDim);
        m = new Pipelines.Materialized(basePipe, m.slotPrefixes(), m.stripped());
        final TypedSpec materializedPipe;
        if (g.versionSweep()) {
            // allVersions(): the RAW extent — every version row, no filter.
            // allVersionsInRange(s, e): versions whose validity window
            // overlaps the range (engine getTemporalMilestoneRangeFilter).
            materializedPipe = g.milestoning().isEmpty() ? basePipe
                    : temporal.rangeMilestonedPipe(basePipe, g.milestoning().get(0),
                            g.milestoning().get(1), g.classFqn());
        } else if (g.forEachDate()) {
            materializedPipe = temporal.forEachDatePipe(basePipe,
                    g.milestoning().get(0), g.classFqn());
        } else if (g.milestoning().size() == 2
                && temporal.temporalStrategy(g.classFqn()) == MilestoningStrategy.BITEMPORAL) {
            // BI-TEMPORAL fetch: .all(processingDate, businessDate) — real
            // pure's getAll(Class, processingDate, businessDate) signature;
            // both dimensions filter.
            materializedPipe = temporal.milestonedPipeByStrategy(
                    temporal.milestonedPipeByStrategy(basePipe, g.milestoning().get(0),
                            MilestoningStrategy.PROCESSING, g.classFqn()),
                    g.milestoning().get(1), MilestoningStrategy.BUSINESS, g.classFqn());
        } else if (g.milestoning().size() == 2) {
            // SINGLE-dimension class with two dates: the RANGE fetch —
            // engine getAll(Class, start, end), same filter as
            // allVersionsInRange
            materializedPipe = temporal.rangeMilestonedPipe(basePipe,
                    g.milestoning().get(0), g.milestoning().get(1), g.classFqn());
        } else {
            materializedPipe = g.milestoning().isEmpty()
                    ? basePipe
                    : temporal.milestonedPipe(basePipe, g.milestoning().get(0), g.classFqn());
        }

        return new RootPipe(m, materializedPipe);
    }

    /** scalar-subquery IN (#78): class-query membership collections
     * resolve up front, identity-keyed for the substitution arm. */
    private Map<TypedSpec, Substitution.InQueryRead> inQueryReadsFor(
            List<TypedSpec> ops, TypedSpec top,
            @com.legend.Nullable List<TypedGraphTree> tree,
            Context context) {
        return InnerDemand.inQueryReads(ops,
                tree == null ? terminalLambdas(top) : List.of(),
                chain -> resolveNode(chain, context));
    }

    /** 2a' JOIN-KEY WIDENING body (extracted from resolveObject): every
     * demanded join/exists condition's source-side key columns must
     * survive the mapping ~distinct narrowing select and the union
     * projection (engine L5135 / partial-union goldens). */
    private static TypedSpec widenPipeForJoinKeys(TypedSpec materializedPipe,
            List<AssociationJoins.AssocJoin> assocJoins,
            Map<String, AssociationJoins.AssocJoin> aggMaterials,
            Map<String, Substitution.ExistsSub> existsSubs) {
        Set<String> joinKeyReads = new LinkedHashSet<>();
        for (AssociationJoins.AssocJoin aj : assocJoins) {
            var ajCondR = aj.condition();
            if (ajCondR != null) { CorrelatedSubselects.collectParamColumnReads(ajCondR, joinKeyReads); }
        }
        for (AssociationJoins.AssocJoin aj : aggMaterials.values()) {
            var ajCondR = aj.condition();
            if (ajCondR != null) { CorrelatedSubselects.collectParamColumnReads(ajCondR, joinKeyReads); }
        }
        for (Substitution.ExistsSub ex : existsSubs.values()) {
            CorrelatedSubselects.collectParamColumnReads(ex.orientedCond(), joinKeyReads);
        }
        if (joinKeyReads.isEmpty()) {
            return materializedPipe;
        }
        // UNION root: member threads carry the demanded join keys
        // through the union projection (engine partial-union goldens)
        return Pipelines.widenConcatenateForKeys(
                Pipelines.widenDistinctForKeys(materializedPipe, joinKeyReads),
                joinKeyReads);
    }

    /** PHASE 2b-ii output: the pipeline with association joins folded
     * (descriptor -> emission, first-demand order) plus the aggregated-
     * navigation materials the fold and substitution both consume. */
    private record JoinedPipe(Pipelines.Materialized m,
            List<AssociationJoins.AssocJoin> aggAssocJoins,
            Map<TypedSpec, Substitution.AggRead> aggReads) {}

    /** PHASE 2b-ii — fold the association joins and the aggregated-
     * navigation grouped subselects onto the materialized pipeline. */
    private JoinedPipe foldAssociationJoins(ClassSource cs,
            Pipelines.Materialized m, TypedSpec keyWidenedPipe,
            List<AssociationJoins.AssocJoin> assocJoins,
            Map<String, AssociationJoins.AssocJoin> aggMaterials,
            Map<String, List<AggDemand>> aggDemands,
            Map<String, AssociationJoins.AssocJoin> chainMids) {
        // 2b. Materialize the association joins (descriptor -> emission,
        //     first-demand order) onto the pipeline.
        TypedSpec withJoins = keyWidenedPipe;
        for (AssociationJoins.AssocJoin aj : assocJoins) {
            TypedSpec joinTarget = aj.targetPipeline();
            Type.RelationType joinTargetRow = aj.targetRow();
            TypedLambda joinCond = aj.condition();
            // ENGINE ON-FORM opt-in (the plain nav-join emitter): the
            // temporal window spells in the join condition, pipe raw
            // (memory milestoning-onclause-seam; exploding subs rebuild
            // their own cond and keep the stamped form)
            if (aj.corrSubPred() == null && aj.onForm() != null) {
                joinTarget = aj.onForm().pipeline();
                joinCond = aj.onForm().condition();
            }
            if (aj.corrSubPred() != null
                    || aj.targetSubNavs().keySet().stream().anyMatch(k ->
                            synthetics.correlatedPred(k) != null)) {
                CorrelatedSubselects.ExplodingSub ex =
                        corrSubs.explodingSubselect(cs, aj,
                                (Type.RelationType) withJoins.info().type());
                joinTarget = ex.target();
                joinTargetRow = ex.row();
                joinCond = ex.cond();
            }
            Type.RelationType leftRow =
                    (Type.RelationType)
                            withJoins.info().type();
            List<Type.Column> cols =
                    new ArrayList<>(leftRow.columns());
            for (Type.Column c
                    : joinTargetRow.columns()) {
                cols.add(new Type.Column(
                        aj.prefix() + c.name(), c.type(), c.multiplicity()));
            }
            withJoins = new TypedJoin(withJoins,
                    joinTarget, leftKind(), java.util.Objects.requireNonNull(joinCond, "joinCond"),
                    Optional.of(aj.prefix()),
                    ViewFrames.frameNameOf(ctx, aj.target()),
                    new ExprType(
                            new Type.RelationType(cols),
                            com.legend.compiler.element.type.Multiplicity.Bounded.ONE),
                false /* resolver-synth */);
        }
        // 2c. AGGREGATED navigations (the engine's subAggregation shape):
        // per to-many head, ONE grouped subselect — the target pipeline
        // grouped by the association's target-side equi-key columns (names
        // preserved, so the association condition joins it VERBATIM), one
        // aggregate column per demand. Each aggregate node then reads its
        // column off the joined row; no row explosion reaches the
        // projection, and the aggregate itself runs IN the database.
        Map<TypedSpec, Substitution.AggRead> aggReads =
                new IdentityHashMap<>();
        List<AssociationJoins.AssocJoin> aggAssocJoins = new ArrayList<>();
        // FILTER-position demands emit their OWN parent-copy subselect;
        // a projection demand on the same head keeps the target-grouped
        // shape (the engine's isolation differs by position — the root
        // tree is copied only into filter isolations)
        List<Map.Entry<String, List<AggDemand>>> aggGroups =
                CorrelatedSubselects.splitAggGroups(aggDemands);
        Set<String> aggJoinHeads = new LinkedHashSet<>();
        Set<String> usedChainPrefixes = new LinkedHashSet<>();
        for (var entry : aggGroups) {
            String head = entry.getKey();
            boolean filterPos = entry.getValue().get(0).filterPosition();
            AssociationJoins.AssocJoin aj = aggMaterials.get(head);
            if (aggJoinHeads.add(head)) {
                aggAssocJoins.add(aj);
            }
            // CHAIN-AGG head (mid.final — the aggScan chain arm): emit the
            // MID hop's LEFT join with a chain-private prefix, then re-point
            // the FINAL hop's parent-side condition reads onto the prefixed
            // columns; the grouped subselect below then keys/joins back
            // against the mid hop's row exactly like a depth-1 parent.
            AssociationJoins.AssocJoin midAj = chainMids.get(head);
            if (midAj != null && aj != null) {
                var cmf = corrSubs.foldChainMid(cs, head, aj, midAj,
                        filterPos, synthetics, withJoins, usedChainPrefixes,
                        ViewFrames.frameNameOf(ctx, midAj.target()));
                withJoins = cmf.withJoins();
                aj = cmf.aj();
            }
            // #69 THE CORRELATED-AGGREGATE SUBSELECT (engine parent-copy
            // architecture): a correlated pred's parent-nav reads can
            // never resolve in the outer ON — the subselect re-joins the
            // PARENT extent (with the navs the pred demands), filters by
            // the pred over the joined row, groups by the PARENT-side
            // equi keys, and joins back on key equality.
            TypedLambda corrAgg = filterPos ? null
                    : synthetics.correlatedPred(head);
            CorrelatedSubselects.CorrAggSub cas =
                    corrSubs.corrAggSubSource(cs, head, java.util.Objects.requireNonNull(aj), corrAgg,
                            filterPos);
            String corrRowVar = cas.rowVar();
            String corrTp = cas.targetPrefix();
            Type.RelationType corrJoinedRow = cas.joinedRow();
            TypedSpec subSource = cas.subSource();
            List<String> keyCols = cas.keyCols();
            Type.RelationType keyRow = cas.keyRow();
            // UNION-mapped parent: equi-keys split per member (ID ->
            // ID_0/ID_1) — group by ALL split columns, join back on OR
            // of same-name pairs (engine unionBase model, task #27 U4)
            List<String> keyCols2 = CorrelatedSubselects
                    .expandSplitKeys(java.util.Objects.requireNonNull(keyCols, "keyCols"), keyRow);
            boolean splitKeys = !keyCols2.equals(keyCols);
            keyCols = keyCols2;
            CorrelatedSubselects.ParentCopy pc = cas.pc();
            List<TypedGroupBy.GroupKey>
                    keys = new ArrayList<>();
            List<Type.Column>
                    subCols = new ArrayList<>();
            CorrelatedSubselects.groupKeysInto(keyCols, keyRow, keys,
                    subCols);
            List<TypedAggCol> aggs =
                    new ArrayList<>();
            int ord = 0;
            var targetRowType = new ExprType(java.util.Objects
                    .requireNonNull(aj, "aj").targetRow(),
                    com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
            for (AggDemand d : entry.getValue()) {
                String alias = "agg_" + ord++;
                aggs.add(corrSubs.aggColFor(cs, head, aj, d, alias,
                        corrAgg, corrTp, corrRowVar, corrJoinedRow,
                        cas.pc()));
                subCols.add(new Type.RelationType
                        .Column(alias, d.node().info().type(),
                        com.legend.compiler.element.type.Multiplicity
                                .Bounded.ZERO_ONE));
            }
            var subRow = new Type.RelationType(subCols);
            TypedSpec sub = new TypedGroupBy(
                    subSource, keys, aggs,
                    new ExprType(subRow,
                            com.legend.compiler.element.type.Multiplicity
                                    .Bounded.ONE));
            String prefix = AssociationJoins.prefixFor(
                    head + (filterPos ? "_fagg" : "_agg"), cs);
            Type.RelationType leftRow =
                    (Type.RelationType)
                            withJoins.info().type();
            List<Type.Column>
                    cols = new ArrayList<>(leftRow.columns());
            for (var c : subRow.columns()) {
                cols.add(new Type.RelationType
                        .Column(prefix + c.name(), c.type(), c.multiplicity()));
            }
            // A JOINED-ROW sub (correlated OR chained parent-copy shape,
            // targetPrefix set) already carries the association condition
            // INSIDE — the outer joins back on parent-key equality only.
            TypedLambda backCond = cas.targetPrefix() == null ? aj.condition()
                    : assocMaterial.pkEqualityCond(keyCols, keyCols,
                            (Type.RelationType)
                                    withJoins.info().type(), subRow,
                            splitKeys);
            withJoins = new TypedJoin(withJoins,
                    sub, leftKind(), java.util.Objects.requireNonNull(backCond, "backCond"),
                    Optional.of(prefix), null,
                    new ExprType(
                            new Type.RelationType(cols),
                            com.legend.compiler.element.type.Multiplicity
                                    .Bounded.ONE),
                false /* resolver-synth */);
            ord = 0;
            for (AggDemand d : entry.getValue()) {
                aggReads.put(d.node(), new Substitution.AggRead(
                        prefix + "agg_" + ord++,
                        CorrelatedSubselects.isCountFamily(d.node())));
            }
        }
        m = new Pipelines.Materialized(withJoins, m.slotPrefixes(), m.stripped());

        return new JoinedPipe(m, aggAssocJoins, aggReads);
    }

    /** PHASE — to-many/navigate heads under exists/isEmpty: the
     * correlated-EXISTS material per head (target pipeline + oriented
     * condition, NO join emitted; the positional rule table §133). */
    /** ops + the terminal: value-position filtered navigation lives in
     * the map/project TERMINAL ($f.emps->filter(..).name — the qualifier
     * family), and its inner predicates demand target slots too. */
    private static List<TypedSpec> withTerminal(List<TypedSpec> ops,
            TypedSpec top) {
        List<TypedSpec> out = new ArrayList<>(ops);
        out.add(top);
        return out;
    }

    private Map<String, Substitution.ExistsSub> registerExistsSubs(
            ClassSource cs, Set<List<String>> paths,
            Set<List<String>> filterPaths, List<TypedSpec> ops,
            Context context, Map<String, Substitution.AssocSub> parentAssocs) {
        Map<String, Substitution.ExistsSub> existsSubs = new LinkedHashMap<>();
        for (List<String> path : paths) {
            String head = path.get(0);
            boolean filterTwoHop = path.size() == 2 && filterPaths.contains(path);
            if ((path.size() != 1 && !filterTwoHop) || existsSubs.containsKey(head)) {
                continue;
            }
            if (cs.bindings().containsKey(SyntheticHeads.realHead(head))) {
                // A NAVIGATE-SLOT head (class-typed Join PM): the nav step
                // carries the target extent + oriented (s, t) predicate.
                var nav = Pipelines.navSteps(cs.pipeline()).get(SyntheticHeads.realHead(head));
                if (nav == null || !(nav.target()
                        instanceof TypedGetAll tg)
                        // eager material only when the target class IS
                        // mapped here (M2M nav targets live upstream —
                        // must not throw for a rewrite that may never fire)
                        || !sources.binds(cs.mappingFqn(), tg.classFqn())) {
                    continue;
                }
                ClassSource t = sources.getForNav(cs.mappingFqn(),
                        tg.classFqn(), head);
                Set<String> tSlots0 = Pipelines.slotAliases(t.pipeline());
                Set<String> tDemand0 = new LinkedHashSet<>();
                Set<String> innerLeaves = new LinkedHashSet<>(
                        InnerDemand.leaves(ops, head));
                for (TypedLambda liftedPred0 : synthetics.allPreds(head)) {
                    for (TypedSpec b : liftedPred0.body()) {
                        InnerDemand.collectParamPathHeads(b,
                                liftedPred0.parameters().get(0), innerLeaves);
                    }
                }
                for (String leaf : innerLeaves) {
                    TypedSpec lb = t.bindings().get(leaf);
                    if (lb != null) {
                        CorrelatedSubselects.collectAliasReads(lb, t.rowVar(), tSlots0, tDemand0);
                    }
                }
                tDemand0 = Pipelines.closeOverConditions(t.pipeline(), tDemand0);
                // #69: a CORRELATED pred's TARGET-side reads may hop the
                // target's OWN class-typed navigate steps ($e.address.name
                // over the navigated rows) — demand those steps, let the
                // materialization join them, and build depth-1 SubNavs for
                // the composition's pass-1 dispatch. Deeper hops stay loud
                // (empty children).
                TypedLambda corrNav0 = synthetics.correlatedPred(head);
                Map<String, String> predNavAliases = new LinkedHashMap<>();
                Set<String> tNavDemand = InnerDemand.navStepDemand(t,
                        Pipelines.navSteps(t.pipeline()).keySet(), corrNav0,
                        synthetics.allPreds(head),
                        InnerDemand.leafChains(ops, head), predNavAliases);
                Pipelines.Materialized tMat0 = tNavDemand.isEmpty()
                        ? Pipelines.materialize(
                                t.pipeline(), tDemand0, t.classFqn())
                        : Pipelines.materialize(
                                t.pipeline(), tDemand0, tNavDemand, t.classFqn(),
                                (al2, tc2) -> Pipelines.materialize(
                                        sources.get(cs.mappingFqn(), tc2)
                                                .pipeline(),
                                        java.util.Set.of(), tc2).pipeline());
                Map<String, Substitution.SubNav> tSubNavs = new LinkedHashMap<>();
                for (var pne : predNavAliases.entrySet()) {
                    String pfx = tMat0.slotPrefixes().get(pne.getValue());
                    var stepT = java.util.Objects.requireNonNull(
                            Pipelines.navSteps(t.pipeline())
                                    .get(pne.getValue())).target();
                    if (pfx == null || !(stepT instanceof TypedGetAll stg)) {
                        continue;
                    }
                    ClassSource sub = sources.get(cs.mappingFqn(), stg.classFqn());
                    tSubNavs.put(pne.getKey(), new Substitution.SubNav(
                            pfx, sub.rowVar(), sub.bindings()));
                }
                // UNION target: member threads carry the key columns the
                // navigate predicate binds on (mirrors the assoc route)
                TypedSpec tPipe0 = tMat0.pipeline();
                if (nav.predicate().parameters().size() == 2) {
                    Set<String> tgtReads = new LinkedHashSet<>();
                    for (TypedSpec b : nav.predicate().body()) {
                        Pipelines.collectVarReads(b,
                                nav.predicate().parameters().get(1), tgtReads);
                    }
                    tPipe0 = Pipelines.widenConcatenateForKeys(tPipe0, tgtReads);
                }
                TypedSpec tTemporal = temporal.temporalTargetPipe(cs, t, head,
                        temporal.applyJoinTemporalFilters(tPipe0, t, Map.of()));
                final ClassSource ft = t;
                // a CORRELATED lifted pred composes into the nav step's own
                // (parent, target) condition — both rows in scope, the same
                // composition as the association route
                TypedLambda navCond = nav.predicate();
                TypedLambda corrNav = corrNav0;
                if (corrNav != null) {
                    navCond = assocMaterial.andCorrelatedIntoCondition(
                            navCond, corrNav, cs, t, tMat0.slotPrefixes(),
                            parentAssocs, tSubNavs);
                }
                final Map<String, Substitution.SubNav> ftSubNavs = tSubNavs;
                tTemporal = synthetics.applyToPipe(head, tTemporal,
                        (p, pred) -> CorrelatedSubselects.predFilteredPipe(p, ft,
                                tMat0.slotPrefixes(), ftSubNavs,
                                pred, cs.mappingFqn()));
                Pipelines.Materialized tMat = new Pipelines.Materialized(
                        tTemporal, tMat0.slotPrefixes(), tMat0.stripped());
                boolean navToMany = !(ctx.findProperty(cs.classFqn(), SyntheticHeads.realHead(head))
                        .map(pr -> pr.multiplicity())
                        .filter(mm -> mm instanceof com.legend.compiler.element.type
                                .Multiplicity.Bounded bb
                                && Integer.valueOf(1).equals(bb.upper()))
                        .isPresent());
                // #70 COMPOSITE chain-backed target: a navigate condition
                // reading a SIBLING JOINSLOT pulls the slot table INTO the
                // target pipeline, correlated outward by hop-1's condition
                // (see CorrelatedSubselects.compositeChainTarget).
                TypedSpec chainPipe = tMat.pipeline();
                if (corrNav == null && navCond.parameters().size() == 2) {
                    CorrelatedSubselects.CompositeChain cc =
                            corrSubs.compositeChainTarget(
                                    cs, navCond, chainPipe);
                    if (cc != null) {
                        chainPipe = cc.pipeline();
                        navCond = cc.orientedCond();
                    }
                }
                NestedScope navNs = nestedScope(t, ops, head, context,
                        chainPipe);
                existsSubs.put(head, new Substitution.ExistsSub(navNs.pipeline(),
                        navCond, t.rowVar(), t.bindings(),
                        navNs.row(),
                        t.classFqn(), Pipelines.slotAliases(t.pipeline()),
                        tMat0.slotPrefixes(), navToMany)
                        .withInnerRegs(navNs.regs())
                        .withSubNavs(tSubNavs));
                continue;
            }
            var assocOpt = ctx.findAssociationOf(cs.classFqn(), SyntheticHeads.realHead(head));
            if (assocOpt.isEmpty()) {
                continue;   // not an association — plain unmapped (loud later)
            }
            // EMBEDDED-ONLY coverage: no root binding for the target
            // class — property mappings override the association
            var hprop = ctx.findProperty(cs.classFqn(),
                    SyntheticHeads.realHead(head)).orElse(null);
            if (hprop != null && hprop.type() instanceof Type.ClassType hct
                    && !sources.binds(cs.mappingFqn(), hct.fqn())) {
                continue;
            }
            // ANY multiplicity: EXISTS material is consumed only under
            // emptiness calls (class-typed isEmpty/isNotEmpty of any mult
            // => [NOT] EXISTS); a bare head keeps the honest H4 story.
            AssociationJoins.AssocJoin aj = assocMaterial.associationJoin(temporal, cs, head, context, true,
                    InnerDemand.leaves(ops, head));
            var assocEnd = assocOpt.get().property1().propertyName()
                    .equals(SyntheticHeads.realHead(head))
                    ? assocOpt.get().property1() : assocOpt.get().property2();
            boolean isToMany = !assocEnd.isToOne();
            // SCALAR (slot-undemanded) pipeline serves value-position
            // consumers; other consumers' slot demand must not fan a
            // single-row subquery out (audit 13 B3). B3 DEFERRED: a
            // separate scalar pipeline regressed real value-leaf reads
            // (testConstraintTargetingMultipleJoins...); stays
            // data-dependent-loud, plumbing (scalarPipeline) in place.
            NestedScope assocNs = nestedScope(aj.target(), ops, head, context,
                    aj.targetPipeline());
            existsSubs.put(head, new Substitution.ExistsSub(assocNs.pipeline(),
                    java.util.Objects.requireNonNull(aj.condition()), aj.target().rowVar(), aj.target().bindings(),
                    assocNs.row(), aj.target().classFqn(),
                    Pipelines.slotAliases(aj.target().pipeline()),
                    aj.targetSlotPrefixes(), isToMany)
                    .withInnerRegs(assocNs.regs()));
        }

        return existsSubs;
    }

    /** PHASE output: the association-route joins (one LEFT join per
     * hop, deduped by chain key) plus per-chain leaf demand. */
    private record AssocPlan(List<AssociationJoins.AssocJoin> assocJoins,
            Map<String, AssociationJoins.AssocJoin> joinsByChain,
            Map<String, Set<String>> leavesByChain) {}

    /** PHASE — association demand (heads that are NOT bindings): one
     * LEFT join per hop chained by prefix, plus SECOND head identities
     * on shared physical slots (2a-x). */
    private AssocPlan registerAssociationJoins(ClassSource cs,
            Set<List<String>> paths, Context context,
            Map<String, TypedNavigate> navSteps,
            Map<String, String> extraNavHeads,
            Map<String, List<List<String>>> extraNavTails,
            Map<String, Substitution.AssocSub> assocs,
            Map<String, String> corrNavHeads,
            Map<String, List<List<String>>> navTailsByAlias) {
        List<AssociationJoins.AssocJoin> assocJoins = new ArrayList<>();
        Map<String, AssociationJoins.AssocJoin> joinsByChain = new LinkedHashMap<>();
        // Per chain-prefix leaf demand: hop 'firm' materializes its OWN
        // slots feeding 'country'.
        Map<String, Set<String>> leavesByChain = new LinkedHashMap<>();
        for (List<String> path : paths) {
            for (int i = 0; i + 1 < path.size(); i++) {
                leavesByChain.computeIfAbsent(String.join(".", path.subList(0, i + 1)),
                        k -> new LinkedHashSet<>()).add(path.get(i + 1));
            }
        }
        Map<String, Set<List<String>>> tailsByChain =
                assocMaterial.chainNavTails(cs, paths);
        for (List<String> path : paths) {
            if (path.size() < 2) {
                continue;
            }
            AssociationJoins.PassThrough emb = null;
            if (cs.bindings().containsKey(SyntheticHeads.realHead(path.get(0)))) {
                // embedded/slot heads: ctor-drillable paths stay
                // substitution-side; a chain LEAVING ctor territory
                // re-roots at the embedded class on the same row and its
                // association hops register below (pass-through)
                emb = assocMaterial.embeddedPassThrough(cs, path);
                if (emb == null) {
                    continue;
                }
            }
            String head = path.get(0);
            // EVERY to-many crossing joins with ROW EXPLOSION — filter
            // position included (engine testInNegated golden: bare LEFT
            // JOIN, surviving rows duplicate the parent; distinct-
            // subselect semi-join reserved for EXPLICIT exists/isEmpty).
            // AUDIT 9: filter-only EXISTS was cardinality-wrong.
            ClassSource parent = cs;
            String parentPrefix = "";
            // $p.assoc.milestoning.from: the struct is a COLUMN read on
            // the assoc target, not a further hop. Audit 23 B3: the
            // GENERATED struct exists only on a TEMPORAL hop class with
            // no DECLARED property of that name (declared wins).
            int effectiveSize = path.size();
            if (path.size() >= 2
                    && path.get(path.size() - 2).equals("milestoning")) {
                String hopCls = CastNav.classAtHop(ctx, cs, path,
                        path.size() - 2);
                if (hopCls != null
                        && com.legend.compiler.element.Temporal
                                .strategyOf(ctx, hopCls) != null
                        && ctx.findProperty(hopCls, "milestoning").isEmpty()) {
                    effectiveSize = path.size() - 1;
                }
            }
            for (int hop = 0; hop + 1 < effectiveSize; hop++) {
                if (emb != null && hop < emb.startHop()) {
                    // pass-through hops: same row, no join — the re-root
                    // class source becomes the parent at the boundary
                    if (hop == emb.startHop() - 1) {
                        parent = emb.root();
                    }
                    continue;
                }
                String chainKey = String.join(".", path.subList(0, hop + 1));
                AssociationJoins.AssocJoin known = joinsByChain.get(chainKey);
                if (known != null) {
                    parent = known.target();
                    parentPrefix = known.prefix();
                    continue;
                }
                if (hop > 0 && synthetics.hasPred(path.get(hop))
                        && synthetics.correlatedPred(path.get(hop)) != null) {
                    // CORRELATED pred on a chained MID hop: parent-copy
                    // reroute serves hop-0 only — loud until the chained
                    // variant. CLOSED preds fall through (associationJoin
                    // parks them on the hop's target pipeline).
                    throw new com.legend.error.NotImplementedException(
                            "correlated filtered navigation as a chained"
                            + " association hop ('"
                            + SyntheticHeads.realHead(path.get(hop))
                            + "' at '" + chainKey + "') is not supported yet");
                }
                AssociationJoins.AssocJoin aj = assocMaterial.associationJoin(temporal, parent, path.get(hop), context, false,
                        leavesByChain.getOrDefault(chainKey, Set.of()), chainKey,
                        tailsByChain.getOrDefault(chainKey, Set.of()));
                if (hop == 0) {
                    // SOURCE-SIDE nested condition reads register the
                    // parent's own assoc join first (navigate() rule)
                    aj = assocMaterial.withSourceNestedAssocs(temporal, cs,
                            aj, context, assocJoins, joinsByChain, assocs);
                }
                if (hop > 0 && Pipelines.containsConcatenate(aj.targetPipeline())) {
                    // union target: paired | routed-lift | wall, plus V4
                    // mid-key parent widen — one arm (AssociationJoins)
                    aj = assocMaterial.chainedUnionHop(temporal, parent, aj,
                            path.get(hop), chainKey, context,
                            leavesByChain.getOrDefault(chainKey, Set.of()),
                            String.join(".", path.subList(0, hop)),
                            joinsByChain, assocJoins);
                }
                if (hop > 0) {
                    // A CHAINED hop: the parent's columns live PREFIXED on the
                    // accumulated joined row — re-point the condition's LEFT
                    // param reads (raw $d.ID -> dept_ID); the hop's own
                    // prefix extends the chain (dept_org_) with hop 0's
                    // collision guard.
                    String chainPrefix = AssociationJoins.chainedPrefix(
                            parentPrefix + path.get(hop), cs, joinsByChain);
                    final String pp2 = parentPrefix;
                    TypedLambda cond = aj.condition();
                    List<Type.Column>
                            leftCols = new ArrayList<>();
                    for (Type.Column c
                            : ((Type.RelationType)
                                    parent.rowType()).columns()) {
                        leftCols.add(new Type.Column(
                                pp2 + c.name(), c.type(), c.multiplicity()));
                    }
                    var leftRow = new Type.RelationType(leftCols);
                    String leftParam = java.util.Objects.requireNonNull(cond).parameters().get(0);
                    TypedSpec body = Pipelines.prefixColumns(
                            cond.body().get(cond.body().size() - 1), leftParam, pp2,
                            v -> new TypedVariable(leftParam,
                                    new ExprType(leftRow,
                                            com.legend.compiler.element.type.Multiplicity
                                                    .Bounded.ONE)));
                    cond = new TypedLambda(cond.parameters(), List.of(body),
                            cond.info());
                    aj = new AssociationJoins.AssocJoin(chainPrefix, aj.target(), aj.targetPipeline(),
                            aj.targetRow(), cond, aj.targetSlotPrefixes(),
                            aj.targetSubNavs(), aj.corrSubPred(), null);
                }
                assocJoins.add(aj);
                joinsByChain.put(chainKey, aj);
                assocs.put(chainKey, new Substitution.AssocSub(aj.prefix(),
                        aj.target().rowVar(), aj.target().bindings(),
                        aj.target().classFqn(),
                        Pipelines.slotAliases(aj.target().pipeline()),
                        aj.targetSlotPrefixes(), null, null,
                        temporal.milestoneColumnsOf(aj.target().pipeline(),
                                aj.target().classFqn()),
                        aj.targetSubNavs()));
                parent = aj.target();
                parentPrefix = aj.prefix();
            }
        }

        // 2a-x. SECOND head identities on one physical slot: an extra
        // prefixed join per identity from the SAME nav material — dotted
        // chainPrefix keys its own temporal spec, a lifted predicate
        // parks inside the target.
        for (var extra : extraNavHeads.entrySet()) {
            String headKey = extra.getKey();
            String alias = extra.getValue();
            var nav = java.util.Objects.requireNonNull(
                    navSteps.get(alias));
            String targetClass = ((TypedGetAll)
                    nav.target()).classFqn();
            ClassSource target = sources.get(cs.mappingFqn(), targetClass);
            NavMaterializer.NavMat mat = navMaterializer.navTargetMaterialized(temporal, cs.mappingFqn(),
                    targetClass,
                    extraNavTails.getOrDefault(headKey, List.of()),
                    headKey, TemporalContext.NONE);
            // the slot route's root stamp comes from the outer join-walk;
            // an extra join never passes it — stamp here (assoc emission)
            TypedSpec tPipe = temporal.temporalTargetPipe(cs, target, headKey,
                    temporal.applyJoinTemporalFilters(mat.pipeline(), target,
                            Map.of()));
            // preds park under the BARE synthetic component — a dotted
            // chain key must strip to it or the identity's pred silently
            // drops (unfiltered join = wrong values)
            String exPredKey = headKey.substring(headKey.lastIndexOf('.') + 1);
            requireNoCorrelatedPred(exPredKey, "navigate-step chain");
            tPipe = synthetics.applyToPipe(exPredKey, tPipe, (p, pred) ->
                    CorrelatedSubselects.predFilteredPipe(p, target, mat.slotPrefixes(),
                            mat.subNavs(), pred, cs.mappingFqn()));
            // a FINGERPRINTED identity prefixes alias_dN_ (NavMaterializer
            // xPrefix rule at root depth) — prefixFor's collision set sees
            // only the base row, never the slot's alias_-prefixed columns
            String exFp = headKey.lastIndexOf('#') >= 0
                    ? headKey.substring(headKey.lastIndexOf('#') + 1) : null;
            AssociationJoins.AssocJoin aj = new AssociationJoins.AssocJoin(exFp != null
                    ? alias + "_" + exFp + "_"
                    : AssociationJoins.prefixFor(headKey, cs), target, tPipe,
                    (Type.RelationType)
                            tPipe.info().type(),
                    AssociationJoins.withOuterDatedWindow(temporal, cs, target,
                            headKey, nav.predicate(), tPipe),
                    mat.slotPrefixes());
            assocJoins.add(aj);
            // the identity's OWN join — outer-dated windows must bind to
            // it, never fall back to the first identity's (twoDatesOneChain)
            joinsByChain.put(headKey, aj);
            assocs.put(headKey, new Substitution.AssocSub(aj.prefix(),
                    target.rowVar(), target.bindings(), target.classFqn(),
                    Pipelines.slotAliases(target.pipeline()),
                    mat.slotPrefixes(), null, null,
                    temporal.milestoneColumnsOf(target.pipeline(), target.classFqn()),
                    mat.subNavs()));
        }

        // 2a-c. #69 CORRELATED-slot reroute: heads whose correlated pred
        // demands a parent NAV left the slot spine — each joins as an
        // AssocJoin carrying the pred for the parent-copy subselect.
        // CLOSED preds still apply in-target.
        for (var ch : corrNavHeads.entrySet()) {
            String headKey = ch.getKey();
            String alias = ch.getValue();
            var nav = java.util.Objects.requireNonNull(
                    navSteps.get(alias));
            String targetClass = ((TypedGetAll)
                    nav.target()).classFqn();
            ClassSource target = sources.get(cs.mappingFqn(), targetClass);
            NavMaterializer.NavMat mat = navMaterializer.navTargetMaterialized(
                    temporal, cs.mappingFqn(), targetClass,
                    navTailsByAlias.getOrDefault(alias, List.of()),
                    headKey, TemporalContext.NONE);
            TypedSpec tPipe = temporal.temporalTargetPipe(cs, target, headKey,
                    temporal.applyJoinTemporalFilters(mat.pipeline(), target,
                            Map.of()));
            tPipe = synthetics.applyToPipe(headKey, tPipe, (p, pred) ->
                    CorrelatedSubselects.predFilteredPipe(p, target, mat.slotPrefixes(),
                            mat.subNavs(), pred, cs.mappingFqn()));
            AssociationJoins.AssocJoin aj = new AssociationJoins.AssocJoin(
                    AssociationJoins.prefixFor(headKey, cs), target, tPipe,
                    (Type.RelationType)
                            tPipe.info().type(),
                    AssociationJoins.withOuterDatedWindow(temporal, cs, target,
                            headKey, nav.predicate(), tPipe),
                    mat.slotPrefixes(), mat.subNavs(),
                    synthetics.correlatedPred(headKey), null);
            assocJoins.add(aj);
            joinsByChain.put(headKey, aj);
            assocs.put(headKey, new Substitution.AssocSub(aj.prefix(),
                    target.rowVar(), target.bindings(), target.classFqn(),
                    Pipelines.slotAliases(target.pipeline()),
                    mat.slotPrefixes(), null, null,
                    temporal.milestoneColumnsOf(target.pipeline(), target.classFqn()),
                    mat.subNavs()));
        }

        return new AssocPlan(assocJoins, joinsByChain, leavesByChain);
    }

    /** Phase 1 output: the collected object-space chain — the (lifted)
     * terminal, the op stack down to the getAll, the effective execution
     * context after in-chain from() re-scoping, and the bound source.
     * Field side-effects (temporal.root(), temporalByHead reset) happen in
     * {@link #collectOpChain} — one construction site. */
    private record OpChain(TypedSpec top, @com.legend.Nullable List<TypedGraphTree> tree,
            boolean implicitSerialize, List<TypedSpec> ops, TypedGetAll getAll,
            Context context, ClassSource cs,
            Map<String, Substitution.AssocSub> flattenAssocs) {}

    /** PHASE 1 — collect the op chain (terminal detection, native-shape
     * normalization, from() re-scoping), validate the fetch, construct
     * THE root temporal context, bind the class source. */
    private OpChain collectOpChain(TypedSpec top, Context context) {
        // PRE-REWRITE (before the demand scan, ledger design): filtered
        // navigations consumed as bare collections lift into SYNTHETIC
        // 2-hop heads whose join target carries the predicate.
        final Context canonCtx = context;
        synthetics.setCanonicalizer(nn -> corrSubs.subTypeNavCastCanon(nn,
                fqn -> dispatch(canonCtx, fqn),
                java.util.Objects.requireNonNull(isNotEmptyCallee(), "isNotEmptyCallee()")));
        top = synthetics.liftFilteredHeads(top);
        // The relation-shaping TERMINAL: project or class-source groupBy
        // (lambdas through the one funnel), or the GRAPH terminals —
        // explicit serialize, and every other class-shaped root = the
        // IMPLICIT serialize over the class's scalar bindings (plan §E10).
        List<TypedGraphTree> tree = null;   // non-null => graph terminal
        boolean implicitSerialize = false;
        Context chainContext = context;     // an in-chain from() re-scopes
        TypedSpec cur;
        if (top instanceof TypedSerialize sz) {
            serializeTypeCfg = sz.config()
                    .map(GraphEmission::serializeTypeConfig).orElse(null);
            checkedEnvelope = sz.source() instanceof TypedGraphFetch g2 && g2.checked();
            tree = sz.tree();
            cur = sz.source() instanceof TypedGraphFetch gf ? gf.source() : sz.source();
        } else if (top instanceof TypedProject t) {
            cur = t.source();
        } else if (top instanceof TypedGroupBy t) {
            cur = t.source();
        } else {
            implicitSerialize = true;
            cur = top;
        }
        // 1. Collect the below-boundary op chain (top-down) to the getAll.
        List<TypedSpec> ops = new ArrayList<>();
        // flatten hops TOPMOST-FIRST; flatSegs.get(i) = ops BELOW hop i
        // (between hop i and the next-deeper hop, or down to the getAll)
        List<String> flattenHops = new ArrayList<>();
        List<List<TypedSpec>> flatSegs = new ArrayList<>();
        while (!(cur instanceof TypedGetAll)) {
            // Normalize collection natives with relation shapes BEFORE
            // collecting: first()/head() IS limit 1; class-space
            // sort(key, comparator) IS sortBy with a direction.
            if (cur instanceof TypedNativeCall nc && Pipelines.isClassDistinct(nc)) {
                // instance distinct = dedup by the SERIALIZED VALUE. Over a
                // single-table extent rows are pk-unique and DISTINCT is a
                // no-op; over a UNION/concatenate extent duplicates are
                // REAL (audit 19d B7 — the old drop-the-node assumption
                // pushed this to a host-side JSON dedup in the harness).
                // Empty column list = the whole materialized row (§A.6).
                cur = new TypedDistinct(nc.args().get(0), List.of(), nc.info());
                continue;
            }
            if (cur instanceof TypedNativeCall nc && isFirstLike(nc)) {
                cur = new TypedLimit(nc.args().get(0),
                        new TypedCInteger(1L, com.legend.compiler.element.type
                                .ExprType.one(com.legend.compiler.element.type
                                        .Type.Primitive.INTEGER)),
                        nc.info());
                continue;
            }
            if (cur instanceof TypedNativeCall nc && isClassToOne(nc)) {
                cur = nc.args().get(0);
                continue;
            }
            if (cur instanceof TypedNativeCall nc && isStaticAt(nc)) {
                // at(k) over instances = the k-th row: slice(k, k+1)
                long k = ((TypedCInteger)
                        nc.args().get(1)).value().longValue();
                cur = new TypedSlice(nc.args().get(0),
                        new TypedCInteger(k, com.legend.compiler.element.type
                                .ExprType.one(com.legend.compiler.element.type
                                        .Type.Primitive.INTEGER)),
                        new TypedCInteger(k + 1, com.legend.compiler.element.type
                                .ExprType.one(com.legend.compiler.element.type
                                        .Type.Primitive.INTEGER)),
                        nc.info());
                continue;
            }
            TypedSortBy asSort = classSortOf(cur);
            if (asSort != null) {
                cur = asSort;
                continue;
            }
            // in-chain from() re-scopes BOTH locals — dispatch reads chainContext (#18 2-binder root cause)
            if (cur instanceof TypedFrom fr) {
                context = chainContext = fromContext(fr, chainContext);
                cur = fr.source();
                continue;
            }
            // ->map(f|$f.assocEnd->...) with a CLASS-result mapper: the
            // flatten IS the mapper body with the source spliced for the
            // param (flatten composition is associative) — keep walking.
            if (cur instanceof TypedMap cm
                    && ((Type.FunctionType) cm.mapper().info().type()).result()
                            .type() instanceof Type.ClassType) {
                cur = substituteParam(cm.mapper(), cm.source());
                continue;
            }
            // CLASS-TERMINAL ASSOCIATION HOP: the flatten boundary — the
            // chain re-roots at the target over the JOIN. EMBEDDED hops
            // never reach here (the funnel's embedded dispatch owns them).
            if (cur instanceof TypedPropertyAccess hp
                    && hp.info().type() instanceof Type.ClassType
                    && hp.source().info().type() instanceof Type.ClassType oc) {
                // ASSOCIATION hops and NAVIGATE-SLOT-mapped class props both
                // flatten; truly EMBEDDED hops hit the assoc loud wall (#63).
                flattenHops.add(hp.property());
                flatSegs.add(new ArrayList<>());
                cur = hp.source();
                continue;
            }
            (flattenHops.isEmpty() ? ops
                    : flatSegs.get(flattenHops.size() - 1)).add(cur);
            cur = switch (cur) {
                case TypedFilter f -> f.source();
                case TypedLimit l -> l.source();
                case TypedDrop d -> d.source();
                case TypedSlice sl -> sl.source();
                case TypedSortBy sb -> sb.source();
                // instance removeDuplicates: whole-row DISTINCT (at worst
                // UNDER-dedups on joined helper columns — honest FAIL)
                case TypedDistinct d -> d.source();
                default -> throw new NotImplementedException("object-space operation "
                        + cur.getClass().getSimpleName() + " is not supported yet");
            };
        }
        TypedGetAll g = (TypedGetAll) cur;
        if (g.forEachDate()) {
            g = new TypedGetAll(g.classFqn(), List.of(   // dates resolve NOW
                    SubQueryLift.resolveDatesRelation(g.milestoning().get(0),
                            chainContext, ctx, specs, letBindings)),
                    false, true, g.info());
        }
        if (g.milestoning().size() > 2) {
            throw new MappingResolutionException("class fetch of '"
                    + g.classFqn() + "' with " + g.milestoning().size()
                    + " milestoning arguments is not supported", g.classFqn());
        }

        if (g.milestoning().isEmpty() && !g.versionSweep()
                && temporal.temporalStrategy(g.classFqn()) != null) {
            // engine: .all() on a temporal class REQUIRES a date argument —
            // an unfiltered extent silently returns every version
            throw new MappingResolutionException("fetch of temporal class '"
                    + g.classFqn() + "' requires a milestoning date argument"
                    + " (use allVersions() for the unfiltered extent)",
                    g.classFqn());
        }
        // M3 temporal context: fresh ROOT frame per getAll (audit 10);
        // calculus = engine getMilestoningContextForAll (M:830-844)
        temporal = TemporalFrame.rootFrame(ctx, sources, letBindings,
                g.forEachDate() ? List.of() : g.milestoning(),
                g.versionSweep(), g.classFqn());
        final Context fctx = chainContext;
        ClassSource cs = sources.get(dispatch(fctx, g.classFqn()), g.classFqn(),
                (t9, ex9) -> sources.dispatch(fctx.explicitMapping(),
                        fctx.runtimeFqn(), fctx.chainMappings(), t9, ex9),
                contextKey(fctx));

        Map<String, Substitution.AssocSub> flattenAssocs = new LinkedHashMap<>();
        // Re-root DEEPEST-FIRST: each flatten joins its hop target onto the
        // accumulated source after applying the segment below it; the hop
        // ABOVE reads off the re-rooted class, so its name joins the demand
        // heads (the target must materialize with that nav/slot step).
        for (int i = flattenHops.size() - 1; i >= 0; i--) {
            cs = flattenSource(cs, flattenHops.get(i), fctx,
                    i == 0 ? ops : flatSegs.get(i - 1),
                    i == 0 ? top : null,
                    i == 0 ? Set.<String>of() : Set.of(flattenHops.get(i - 1)),
                    flattenAssocs, flatSegs.get(i),
                    top instanceof TypedProject || top instanceof TypedGroupBy);
        }
        return new OpChain(top, tree, implicitSerialize, ops, g, context, cs,
                flattenAssocs);
    }

    /** Milestoned property functions: each head's temporal arguments,
     * chain-keyed (conflicting dates for one chain are loud — the date
     * split renamed genuine two-date heads before this runs). */
    private Map<String, TemporalFrame.TemporalSpec> collectChainSpecs(
            List<TypedSpec> ops, TypedSpec top,
            @com.legend.Nullable List<TypedGraphTree> tree) {
        Map<String, TemporalFrame.TemporalSpec> specs =
                new LinkedHashMap<>();
        for (TypedSpec op : ops) {
            if (op instanceof TypedFilter f) {
                temporal.collectTemporalSpecs(f.predicate(), specs);
            }
            if (op instanceof TypedSortBy sb) {
                temporal.collectTemporalSpecs(sb.key(), specs);
            }
        }
        if (tree == null) {
            for (TypedLambda fn : terminalLambdas(top)) {
                temporal.collectTemporalSpecs(fn, specs);
            }
        } else {
            temporal.collectTreeSweeps(tree, specs);
        }
        return specs;
    }

    private TypedSpec resolveObject(TypedSpec top, Context context) {
        OpChain phase1 = collectOpChain(top, context);
        List<TypedSpec> ops = phase1.ops();
        top = DateSplit.splitDatedHeads(ops, phase1.top(), temporal, synthetics);
        List<TypedGraphTree> tree = phase1.tree();
        boolean implicitSerialize = phase1.implicitSerialize();
        TypedGetAll g = phase1.getAll();
        context = phase1.context();
        ClassSource cs0 = phase1.cs();

        // 2. Demand scan over ALL the chain's user lambdas (one funnel with
        //    the substitution — they cannot drift), close over slot
        //    conditions, materialize.
        // POSITION-AWARE demand (the positional rule table): to-many paths
        // in PROJECTION position explode via LEFT JOIN; in FILTER position
        // they become implicit EXISTS per boolean leaf.
        // ENTRY RULE (learned three times now): scans enter through the
        // lambda's BODY — entering via the lambda itself trips the shadow
        // stop on its own parameter.
        Set<List<String>> filterPaths = new LinkedHashSet<>();
        Set<List<String>> projectionPaths = new LinkedHashSet<>();
        Map<String, List<AggDemand>> aggDemands =
                new LinkedHashMap<>();
        collectOpDemand(ops, cs0, filterPaths, projectionPaths, aggDemands);
        CorrelatedSubselects.aggScanFilters(ops, cs0, aggDemands,
                this::isToManyAssocHead, this::isAssocOrNavHead);
        if (tree == null && implicitSerialize) {
            tree = new GraphEmission(ctx, sources, assocMaterial, temporal, this::dispatch, () -> freshVarCounter++).synthesizeScalarTree(cs0);
        }
        if (tree != null) {
            // GRAPH terminal: LEAF paths feed slot demand; class-typed
            // children correlate — buildGraphNode materializes them
            InnerDemand.treeDemandPaths(tree, cs0, ctx, projectionPaths);
        } else {
            for (TypedLambda fn : terminalLambdas(top)) {
                for (TypedSpec b : fn.body()) {
                    CorrelatedSubselects.aggScan(b, fn.parameters().get(0), cs0,
                            aggDemands, projectionPaths,
                            this::isToManyAssocHead, this::isAssocOrNavHead);
                }
                synthetics.corrPredOuterDemand(fn, projectionPaths);
            }
        }
        Map<TypedSpec, Substitution.InQueryRead> inQueryReads =
                inQueryReadsFor(ops, top, tree, context);
        Set<List<String>> paths = new LinkedHashSet<>(filterPaths);
        paths.addAll(projectionPaths);

        Map<String, TemporalFrame.TemporalSpec> chainSpecs =
                collectChainSpecs(ops, top, tree);
        temporal = temporal.withSpecs(chainSpecs);

        // NAV-DATE (#32): a spec date that READS A NAVIGATION off the
        // parent ($o.product($o.orderDetails.settlementDate)) demands
        // that chain like any other read; its step SINKS below every
        // consuming head join (materializeRoot) so the composed date
        // column sits on the head's LEFT row for the outer-date window.
        paths = InnerDemand.withNavDatePaths(paths, chainSpecs.values());

        // View-join pruning on the FRAME path: un-read join-navigating
        // view columns release their frame slots (Pipelines.narrowFrameSource)
        final ClassSource cs = Pipelines.narrowFrameSource(cs0, paths);

        NavPlan navPlan = registerNavigations(cs, paths,
                InnerDemand.occurrenceSplitChains(filterPaths, projectionPaths));
        Set<String> dateAliases = InnerDemand.navDateAliases(
                chainSpecs.values(), navPlan.navHeadByAlias());
        Set<String> demanded = navPlan.demanded();
        Set<String> demandedNavs = navPlan.demandedNavs();
        Map<String, Substitution.AssocSub> assocs = navPlan.assocs();
        assocs.putAll(phase1.flattenAssocs());   // #63 flatten provenance
        Map<String, NavMaterializer.NavMat> navMats = navPlan.navMats();
        Map<String, String> navHeadByAlias = navPlan.navHeadByAlias();
        Map<String, String> extraNavHeads = navPlan.extraNavHeads();
        Map<String, List<List<String>>> extraNavTails =
                navPlan.extraNavTails();
        var navSteps = navPlan.navSteps();

        // Mapping ~distinct stays IN the pipeline (a distinct subselect —
        // the engine's own emission, its "could optimize to collapse" TODO
        // notwithstanding): deferring it to the projected output dedups
        // over the PROJECTED SUBSET of columns, which changes row counts
        // whenever the projection is not injective on the distinct tuple
        // (corpus testDistinctMappingSimpleProjectSelectOneOfTheDistinct-
        // Properties: name-only projection must keep BOTH 'IF 2' rows).
        RootPipe rootPipe = materializeRoot(cs, g, demanded, demandedNavs,
                navMats, navHeadByAlias, assocs, dateAliases);
        Pipelines.Materialized m = rootPipe.m();
        final TypedSpec materializedPipe = rootPipe.materializedPipe();

        Map<String, Substitution.ExistsSub> existsSubs =
                registerExistsSubs(cs, paths, filterPaths,
                        withTerminal(ops, top), context, assocs);

        AssocPlan assocPlan = registerAssociationJoins(cs, paths, context,
                navSteps, extraNavHeads, extraNavTails, assocs,
                navPlan.corrNavHeads(), navPlan.navTails());
        List<AssociationJoins.AssocJoin> assocJoins = assocPlan.assocJoins();
        Map<String, AssociationJoins.AssocJoin> joinsByChain = assocPlan.joinsByChain();

        // subType(@Sub) casts dispatch through the SUBTYPE's binding
        // table (same-source inheritance) — registered after
        // materialization so the scan never perturbs join demand
        CorrelatedSubselects.registerSubTypeSubs(cs, top, sources, assocs);

        // 2a'. JOIN-KEY COLLECTION under mapping ~distinct (engine L5135):
        // demanded joins' source-side key columns must survive the
        // ~distinct narrowing select — widen it (the distinct then dedups
        // over the widened row, exactly the engine's query-dependent
        // distinct tuple). Aggregated-navigation materials build here so
        // their conditions participate.
        Map<String, AssociationJoins.AssocJoin> chainMids =
                new LinkedHashMap<>();
        Map<String, AssociationJoins.AssocJoin> aggMaterials =
                corrSubs.buildAggMaterials(temporal, cs, context, aggDemands,
                        chainMids);
        TypedSpec keyWidenedPipe = widenPipeForJoinKeys(materializedPipe,
                assocJoins, aggMaterials, existsSubs);
        if (keyWidenedPipe != materializedPipe) {
            m = new Pipelines.Materialized(keyWidenedPipe, m.slotPrefixes(),
                    m.stripped());
        }

        registerDottedExistsSubs(cs, ops, top, tree, context, assocs,
                existsSubs);

        JoinedPipe joined = foldAssociationJoins(cs, m, keyWidenedPipe,
                assocJoins, aggMaterials, aggDemands, chainMids);
        m = joined.m();
        // form-2 outer-nav dates: windows over the JOINED frame (Leg 2)
        m = temporal.applyOuterNavDateFilters(cs, m, joinsByChain);
        List<AssociationJoins.AssocJoin> aggAssocJoins = joined.aggAssocJoins();
        Map<TypedSpec, Substitution.AggRead> aggReads = joined.aggReads();

        // Association-end names for honest bare-head errors (audit R3).
        Set<String> assocEnds = new LinkedHashSet<>(assocs.keySet());
        for (List<String> path : paths) {
            if (!cs.bindings().containsKey(path.get(0))
                    && ctx.findAssociationOf(cs.classFqn(), path.get(0)).isPresent()) {
                assocEnds.add(path.get(0));
            }
        }

        // 3. Fold the ops back on, bottom-up, substituting filter lambdas.
        // Fresh row var must not collide with any lambda param in reach
        // (user lambdas may legally be named _rN); scan and skip.
        String fresh = CorrelatedSubselects.freshRowVar(cs, ops, top,
                assocJoins, aggAssocJoins, existsSubs,
                () -> freshVarCounter++);
        TypedSpec pipeline = m.pipeline();
        for (int i = ops.size() - 1; i >= 0; i--) {
            pipeline = switch (ops.get(i)) {
                case TypedFilter f -> new TypedFilter(pipeline,
                        substitution(cs, m, assocs, assocEnds, existsSubs, aggReads, inQueryReads, true, fresh, f.predicate(), context)
                                .rewriteLambda(f.predicate()),
                        pipeline.info());
                case TypedLimit l -> new TypedLimit(pipeline, l.count(), pipeline.info());
                case TypedDrop d -> new TypedDrop(pipeline, d.count(), pipeline.info());
                case TypedSlice sl -> new TypedSlice(pipeline, sl.start(), sl.stop(),
                        pipeline.info());
                case TypedSortBy sb -> new TypedSortBy(pipeline,
                        substitution(cs, m, assocs, assocEnds, existsSubs, aggReads, inQueryReads, false, fresh, sb.key(), context).rewriteLambda(sb.key()),
                        sb.ascending(), sb.keyAlias(), pipeline.info());
                case TypedDistinct d -> {
                    // dedup by the MAPPED row (engine instance identity):
                    // narrow to the class's own columns so joined helper
                    // columns cannot defeat the dedup (the two-exists
                    // family's 'expected 1, got 5'); downstream demands on
                    // dropped columns fail loud at projection resolution.
                    Type.RelationType pipeRow =
                            (Type.RelationType) pipeline.info().type();
                    Set<String> own = new LinkedHashSet<>();
                    for (Type.Column c : cs.rowType().columns()) {
                        own.add(c.name());
                    }
                    List<Type.Column> kept = pipeRow.columns().stream()
                            .filter(c -> own.contains(c.name())).toList();
                    if (kept.isEmpty()) {
                        yield new TypedDistinct(pipeline, List.of(),
                                pipeline.info());
                    }
                    yield new TypedDistinct(pipeline,
                            kept.stream().map(Type.Column::name).toList(),
                            new ExprType(new Type.RelationType(kept),
                                    pipeline.info().multiplicity()));
                }
                default -> throw new IllegalStateException("resolver bug: uncollected op");
            };
        }

        if (tree != null) {   // 4a. GRAPH terminal (H4a snapshot envelope)
            TypedSerializeGraph env = new GraphEmission(ctx, sources, assocMaterial, temporal, this::dispatch, () -> freshVarCounter++)
                    .buildGraphNode(cs, pipeline, m.slotPrefixes(), m.stripped(), fresh, tree, context, /*arrayWrap*/ true, g.info(), checkedEnvelope);
            return serializeTypeCfg == null ? env : GraphEmission.withTypeKey(
                    env, serializeTypeCfg, GraphEmission.stringPlusCallee(ctx),
                    serializeTypeCfg.includeObjectReference() ? GraphEmission.asorPrefix(ctx, cs) : null);
        }

        // 4. The relation-shaping boundary: info UNCHANGED.
        final TypedSpec base = pipeline;
        final Pipelines.Materialized fm = m;
        final String fv = fresh;
        final Context fcx = context;
        Function<TypedLambda, TypedLambda> sub = fn ->
                substitution(cs, fm, assocs, assocEnds, existsSubs, aggReads, inQueryReads, false, fv, fn, fcx)
                        .rewriteLambda(fn);
        // An agg map may be the BARE instance var (x|$x : y|$y->count()) —
        // COUNT(*)-style; it becomes the identity over the row.
        Function<TypedAggCol, TypedAggCol> subAgg = a ->
                new TypedAggCol(a.name(),
                        isBareUserVar(a.map())
                                ? substitution(cs, fm, assocs, assocEnds, existsSubs,
                                        aggReads, inQueryReads, false, fv,
                                        a.map()).identityLambda(a.map())
                                : sub.apply(a.map()),
                        a.reduce(),
                        a.orderKey() == null ? null : sub.apply(a.orderKey()),
                        a.orderAsc());
        return switch (top) {
            case TypedProject p -> new TypedProject(base,
                    p.columns().stream().map(col -> new TypedFuncCol(col.name(),
                            sub.apply(col.fn()), col.documentation())).toList(),
                    p.info());
            case TypedGroupBy gb -> new TypedGroupBy(base,
                    gb.keys().stream().map(k -> new TypedGroupBy.GroupKey(k.column(),
                            Optional.of(sub.apply(k.fn().orElseThrow(() ->
                                    new NotImplementedException("class-source groupBy"
                                            + " key '" + k.column() + "' without an"
                                            + " extraction lambda is not supported"
                                            + " yet")))))).toList(),
                    gb.aggs().stream().map(subAgg).toList(),
                    gb.info());
            default -> throw new IllegalStateException("unreachable");
        };
    }

    /** The object-space lambdas a relation-shaping terminal carries. */
    static List<TypedLambda> terminalLambdas(TypedSpec top) {
        List<TypedLambda> out = new ArrayList<>();
        switch (top) {
            case TypedProject p -> p.columns().forEach(c -> out.add(c.fn()));
            case TypedGroupBy g -> {
                g.keys().forEach(k -> k.fn().ifPresent(out::add));
                g.aggs().forEach(a -> out.add(a.map()));
            }
            default -> throw new IllegalStateException("unreachable");
        }
        return out;
    }

    /** {@code x|$x} — the whole-instance map of a COUNT(*)-style aggregate. */
    private static boolean isBareUserVar(TypedLambda l) {
        return l.body().size() == 1
                && l.body().get(0) instanceof TypedVariable v
                && l.parameters().size() == 1
                && v.name().equals(l.parameters().get(0));
    }

    static void collectLambdaParams(TypedSpec n, Set<String> out) {
        if (n instanceof TypedLambda l) {
            out.addAll(l.parameters());
        }
        for (TypedSpec c : n.children()) {
            collectLambdaParams(c, out);
        }
    }

    /** The navigate-slot alias a class-typed head binding reads, or null. */

    /**
     * A lifted head's user predicate, substituted over the TARGET's
     * bindings (the {@code rewriteExists} cfSub pattern applied at the
     * materialization site) and wrapped around the finished target
     * pipeline — the join's composite right side carries the filter
     * INSIDE (engine JTN parity), so unmatched parents keep their NULL
     * row and the outer join-stamping never double-stamps.
     */
        /** As above with the target's MATERIALIZED nav-step SubNavs: a pred's
     * depth-2 reads through class-typed slot heads ($e.address.name — the
     * Fork family) dispatch through AssocSub registries built from them
     * (the corrPredOnJoinedRow pass-1 rule, shared). */
            /** The demand half of the shared funnel: every $p.<path> read in a lambda. */
    static void consumedPaths(TypedSpec n, String userVar,
                                      Set<List<String>> out) {
        List<String> path = Substitution.pathOf(n, userVar);
        if (path != null) {
            out.add(path);
        }
        InnerDemand.composeAutoMapPaths(n, userVar, out, StoreResolver::consumedPaths);
        InnerDemand.scanTdsContainsFns(n, userVar, (b, pv) -> consumedPaths(b, pv, out));
        if (n instanceof TypedLambda l && l.parameters().contains(userVar)) {
            return;   // shadowing: the substitution stops here too (one funnel)
        }
        for (TypedSpec c : n.children()) {
            consumedPaths(c, userVar, out);
        }
    }

    /** Aggregate natives that reduce a to-many navigation in projection
     * position (exact FQNs from the catalog — never name suffixes). */
            /** COUNT over no children is pure 0 — the LEFT join delivers NULL. */
        /** One aggregate call over a to-many association path in projection
     * position: {@code $f.employees.age->max()}. Substitutes as a column
     * read off the head's grouped-subselect join (engine subAggregation
     * shape) — the path is NOT bare-demanded, so no row explosion. */
    /** The class FQN reached after {@code upto} property hops from the
     * source class; null when any hop is not a class-typed property. */
    record AggDemand(TypedNativeCall node,
            @com.legend.Nullable String leaf, @com.legend.Nullable TypedLambda mapper,
            @com.legend.Nullable TypedLambda orderKey, boolean orderAsc,
            boolean filterPosition) {

        AggDemand(TypedNativeCall node, @com.legend.Nullable String leaf) { this(node, leaf, null, null, true, false); }
        AggDemand(TypedNativeCall node, @com.legend.Nullable String leaf,
                @com.legend.Nullable TypedLambda mapper) { this(node, leaf, mapper, null, true, false); }
        AggDemand(TypedNativeCall node, @com.legend.Nullable String leaf,
                @com.legend.Nullable TypedLambda mapper,
                @com.legend.Nullable TypedLambda orderKey, boolean orderAsc) {
            this(node, leaf, mapper, orderKey, orderAsc, false);
        }

        /** Target-side property heads this demand reads — feeds the
         * target's slot demand. */
        List<String> demandLeaves() {
            List<String> out = new ArrayList<>();
            if (leaf != null) {
                out.add(leaf);
            } else {
                for (List<String> pth : lambdaHeads(
                        java.util.Objects.requireNonNull(mapper, "mapper"))) {
                    out.add(pth.get(0));
                }
            }
            if (orderKey != null) {
                for (List<String> pth : lambdaHeads(orderKey)) {
                    out.add(pth.get(0));
                }
            }
            return out;
        }

        private static Set<List<String>> lambdaHeads(TypedLambda fn) {
            Set<List<String>> ps = new LinkedHashSet<>();
            for (TypedSpec b : fn.body()) {
                consumedPaths(b, fn.parameters().get(0), ps);
            }
            return ps;
        }
    }

    /**
     * The agg-aware projection-position scan: aggregates over TO-MANY
     * association paths register {@link AggDemand}s; every OTHER path is
     * bare demand exactly as {@link #consumedPaths} records it (one
     * traversal — the two demand kinds cannot double-count a path).
     */

    /**
     * The FILTER-position scan: a bare to-many crossing consumed AS A
     * COLLECTION by contains/in is set MEMBERSHIP (EXISTS route — engine
     * testContainsOnToManyProperty golden) and demands only its HEAD's
     * exists material, never the explosion join; everything else records
     * bare demand exactly as {@link #consumedPaths}.
     */
    private void memberScan(TypedSpec n, String userVar, ClassSource cs,
                            Set<List<String>> out) {
        if (n instanceof TypedNativeCall mc
                && mc.args().size() == 2) {
            String key = mc.callee().signatureKey();
            boolean isContains = Pure.nativeNamed("contains", key);
            boolean isIn = Pure.nativeNamed("in", key);
            if (isContains || isIn) {
                TypedSpec coll = isContains ? mc.args().get(0) : mc.args().get(1);
                TypedSpec other = isContains ? mc.args().get(1) : mc.args().get(0);
                List<String> cp = coll
                        instanceof TypedPropertyAccess
                        ? Substitution.pathOf(coll, userVar) : null;
                if (cp != null && cp.size() == 2 && isToManyAssocHead(cs, cp.get(0))) {
                    out.add(List.of(cp.get(0)));
                    memberScan(other, userVar, cs, out);
                    return;
                }
            }
        }
        InnerDemand.scanTdsContainsFns(n, userVar,
                (b, pv) -> memberScan(b, pv, cs, out));
        // FILTER-POSITION to-many aggregate (audit 9's join-explosion
        // hazard): the node routes through the AGG DEMAND SCAN (the same
        // parent-copy grouped-subselect machinery as projection position —
        // aggregates are single-row, so the joined column compares safely
        // in WHERE). memberScan SKIPS it (its nav path must not become an
        // implicit EXISTS); a shape the agg scan fails to register still
        // dies loud at the Substitution backstop ("the aggregate demand
        // scan did not recognize this shape").
        if (n instanceof TypedNativeCall ac
                && !ac.args().isEmpty()
                && CorrelatedSubselects.isAggregate(ac.callee())
                && CorrelatedSubselects.containsToManyCrossing(
                        ac.args().get(0), userVar, cs,
                        this::isToManyAssocHead)) {
            return;
        }
        List<String> path = Substitution.pathOf(n, userVar);
        if (path != null) {
            out.add(path);
        }
        if (n instanceof TypedLambda l && l.parameters().contains(userVar)) {
            return;
        }
        for (TypedSpec c : n.children()) {
            memberScan(c, userVar, cs, out);
        }
    }

    /** {@code head} is a to-many navigation: an unbound association end, or
     * a navigate-slot binding (class-typed Join PM), with to-many
     * multiplicity on the class property. */
    private boolean isToManyAssocHead(ClassSource cs, String head) {
        // synthetic identities (#fN/#cN/#dN) route by their REAL property —
        // an aggregate over a lifted head must take the grouped-subselect
        // route, never bare-explode (wrong row counts, silent)
        String real = SyntheticHeads.realHead(head);
        // findProperty misses ASSOCIATION-DECLARED ends (the modelJoin/
        // XStore domains declare them on the Association element only —
        // same gap as chainNavTails' hopTargetClass): fall through to the
        // association end's own multiplicity.
        boolean toMany = ctx.findProperty(cs.classFqn(), real)
                .map(pr -> !(pr.multiplicity()
                        instanceof com.legend.compiler.element.type.Multiplicity.Bounded b
                        && Integer.valueOf(1).equals(b.upper())))
                .orElseGet(() -> ctx.findAssociationOf(cs.classFqn(), real)
                        .map(a -> !(a.property1().propertyName().equals(real)
                                ? a.property1() : a.property2()).isToOne())
                        .orElse(false));
        return toMany && isAssocOrNavHead(cs, real);
    }

    /** See {@link AssociationJoins#isAssocOrNavHead} (relocated). */
    boolean isAssocOrNavHead(ClassSource cs, String head) { return assocMaterial.isAssocOrNavHead(cs, head); }

    /**
     * The TARGET-side key columns of a conjunctive equi-join condition —
     * the columns that pin each source row to AT MOST ONE group of the
     * aggregated subselect. Any other condition shape is loud: joining a
     * grouped subselect on it could match multiple groups (fan-out).
     */
    /** PARENT-side equi columns of the association condition (roles
     * swapped vs {@link #targetEquiKeys}) — the group/join-back keys of
     * the correlated aggregated subselect (#69 parent-copy emission). */

    /** #69 aggregated-navigation materials: per head, the target join
     * material + demanded nav paths. Correlated preds re-join the PARENT
     * extent (parent-copy: pipeline materialized with the outer reads'
     * slots + depth-1 SubNavs; deeper hops loud), filter by the pred,
     * group by parent-side equi keys; uncorrelated heads group the plain
     * target. Temporal context (M3): root dates/strategy flow to
     * SAME-STRATEGY targets through temporal parents only. */
    static TypedEnumValue leftKind() {
        String fqn = "meta::pure::functions::relation::JoinKind";
        return new TypedEnumValue(fqn, "LEFT",
                new ExprType(
                        new Type.EnumType(fqn),
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
    }

    /** INNER — the flatten's null-row guard by construction: the engine
     * spells LEFT and its READER skips null-pk rows; the row sets are
     * identical (deliberate documented emission divergence). */
    static TypedEnumValue innerKind() {
        String fqn = "meta::pure::functions::relation::JoinKind";
        return new TypedEnumValue(fqn, "INNER",
                new ExprType(
                        new Type.EnumType(fqn),
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
    }

    /**
     * Association-join materials for {@code $parent.head}: the mapping's
     * AssociationBinding predicate fn carries the condition (H1's
     * legacyAssocPredicate emission); the target = the class's own
     * pipeline (~filter rides; slots strip under empty demand — leaf
     * reads of them are loud). Orientation: cond params are (classA-row,
     * classB-row), classA = property1's target — navigating property1
     * REVERSES params (TypedJoin binds (parent, target)). R1 RECURSIVE
     * SCOPE DEMAND: exists/filter predicates nested under head get their
     * own materials against the TARGET class (Registries.NONE was a
     * blanket stop); terminates on expression depth (R1a: exists
     * materials only).
     */
    /** The nested scope's registries PLUS the target pipeline widened with
     * the nested association joins (their prefixed columns must ride the
     * exists relation for assocLeaf reads — R2 arm 2). */
    record NestedScope(Substitution.Registries regs, TypedSpec pipeline,
                       Type.RelationType row) {
    }

    private NestedScope nestedScope(ClassSource t, List<TypedSpec> ops,
            String head, Context context, TypedSpec targetPipe) {
        return nestedScope(t, ops, List.of(head), context, targetPipe);
    }

    private NestedScope nestedScope(ClassSource t,
            List<TypedSpec> ops, List<String> pathKey, Context context,
            TypedSpec targetPipe) {
        Substitution.Registries none = Substitution.Registries.NONE;
        List<TypedLambda> inner = InnerDemand.lambdas(ops, pathKey);
        Type.RelationType row =
                (Type.RelationType) targetPipe.info().type();
        if (inner.isEmpty()) {
            return new NestedScope(none, targetPipe, row);
        }
        Set<List<String>> innerPaths = new LinkedHashSet<>();
        Set<List<String>> innerFullPaths = new LinkedHashSet<>();
        List<TypedSpec> innerOps = new ArrayList<>();
        for (TypedLambda lam : inner) {
            if (lam.parameters().isEmpty()) {
                continue;
            }
            Set<String> heads = new LinkedHashSet<>();
            InnerDemand.collectParamPathHeads(lam, lam.parameters().get(0), heads);
            heads.forEach(h -> innerPaths.add(List.of(h)));
            // FULL paths feed depth-2+ leaf/nav demand (heads-only lost
            // the tails — multi-hop exists family, #70/#78)
            for (TypedSpec b : lam.body()) {
                consumedPaths(b, lam.parameters().get(0), innerFullPaths);
            }
            innerOps.add(new TypedFilter(targetPipe, lam, targetPipe.info()));
        }
        if (innerPaths.isEmpty()) {
            return new NestedScope(none, targetPipe, row);
        }
        return scopeMaterials(t, innerPaths, innerFullPaths, innerOps,
                context, targetPipe, pathKey);
    }

    /** A flatten splice's below-op scope: the factory-widened pipeline
     * and the substitution rewriter over it — ONE construction for all
     * three flatten splices (nav-slot, assoc-route, re-root; slice 3 of
     * docs/NESTED_SCOPE_REGISTRIES.md — the latter two ran with
     * {@code Map.of()} registries). */
    private record BelowScope(TypedSpec pipeline,
            java.util.function.Function<TypedLambda, TypedLambda> sub) {
    }

    private BelowScope belowScope(ClassSource src, List<TypedSpec> ops,
            Context context, TypedSpec targetPipe) {
        FlattenOps.BelowSplit bsp = FlattenOps.splitBelowOps(ops, src,
                null, Pipelines.navSteps(src.pipeline()).keySet());
        Set<List<String>> bHeads = new LinkedHashSet<>();
        bsp.spliceFull().forEach(p -> bHeads.add(List.of(p.get(0))));
        NestedScope bs = scopeMaterials(src, bHeads, bsp.spliceFull(),
                ops, context, targetPipe, List.of());
        Pipelines.Materialized base = Pipelines.materialize(
                src.pipeline(), java.util.Set.of(), src.classFqn());
        Pipelines.Materialized bm = new Pipelines.Materialized(
                bs.pipeline(), base.slotPrefixes(), base.stripped());
        String bv = CorrelatedSubselects.freshRowVar(src, ops,
                src.pipeline(), List.of(), List.of(), Map.of(),
                () -> freshVarCounter++);
        return new BelowScope(bs.pipeline(),
                fn -> substitution(src, bm, bs.regs().assocs(), Set.of(),
                        bs.regs().existsSubs(), Map.of(), Map.of(), true,
                        bv, fn).rewriteLambda(fn));
    }

    /** THE registry factory (docs/NESTED_SCOPE_REGISTRIES.md): materials
     * for ANY scope consuming {@code paths} against {@code t} over
     * {@code targetPipe} — exists + assoc/chain registration, and the
     * pipeline WIDENED with the nested joins (R2 arm 2: the ordering the
     * reverted hand-rolled attempts got wrong). */
    private NestedScope scopeMaterials(ClassSource t,
            Set<List<String>> innerPaths, Set<List<String>> innerFullPaths,
            List<TypedSpec> innerOps, Context context, TypedSpec targetPipe,
            List<String> pathKey) {
        Substitution.Registries none = Substitution.Registries.NONE;
        Type.RelationType row =
                (Type.RelationType) targetPipe.info().type();
        // DATED-HOP cursor: nested registrations run under the hop's
        // own frame (root = hop date, specs re-keyed locally)
        TemporalFrame outerT = temporal;
        TemporalFrame nf = temporal.nestedFrame(t.classFqn(),
                String.join(".", pathKey));
        if (nf != null) {
            temporal = nf;
        }
        try {
        // nested EXISTS materials (emptiness consumption)
        Map<String, Substitution.ExistsSub> nested =
                registerExistsSubs(t, innerPaths, Set.of(), innerOps, context, Map.of());
        // nested ASSOC materials (leaf reads) + deep chain keys — built
        // with the correlated-scope machinery (guardrail extraction)
        CorrelatedSubselects.NestedMaterials nm = corrSubs
                .nestedAssocMaterials(temporal, context, t, targetPipe,
                        innerPaths, innerFullPaths,
                        (cls, prop) -> !ctx.findAssociationOf(cls, prop)
                                .isEmpty());
        Map<String, Substitution.AssocSub> nestedAssocs = nm.assocs();
        TypedSpec pipe = nm.pipe();
        if (nested.isEmpty() && nestedAssocs.isEmpty()) {
            // callees still ride (objectReferenceIn in a nested predicate)
            return new NestedScope(new Substitution.Registries(Map.of(),
                    Set.of(), Map.of(), Map.of(), Map.of(), isNotEmptyCallee(),
                    equalCallee(), List.of(), inCallee(), boolCallee("and"),
                    boolCallee("or")), targetPipe, row);
        }
        return new NestedScope(
                new Substitution.Registries(nestedAssocs, Set.of(), nested,
                        Map.of(), Map.of(), isNotEmptyCallee(), equalCallee(),
                        List.of(), inCallee()),
                pipe, (Type.RelationType) pipe.info().type());
        } finally {
            temporal = outerT;
        }
    }

    /** Whether the pipeline contains a UNION (concatenate) anywhere. */
    /** Whether the pipeline carries a mapping ~filter anywhere. */
    static boolean containsFilter(TypedSpec pipeline) {
        if (pipeline instanceof TypedFilter) {
            return true;
        }
        for (TypedSpec c : pipeline.children()) {
            if (containsFilter(c)) {
                return true;
            }
        }
        return false;
    }

    /** Context-less scopes (hop/nested registries): whole-source cast
     * heads stay at their loud wall there. */
    private Substitution substitution(ClassSource cs, Pipelines.Materialized m,
                                      Map<String, Substitution.AssocSub> assocs,
                                      Set<String> assocEnds,
                                      Map<String, Substitution.ExistsSub> existsSubs,
                                      Map<TypedSpec, Substitution.AggRead> aggReads,
                                      Map<TypedSpec, Substitution.InQueryRead> inQueryReads,
                                      boolean filterPosition,
                                      String freshRowVar, TypedLambda userLambda) {
        return substitution(cs, m, assocs, assocEnds, existsSubs, aggReads,
                inQueryReads, filterPosition, freshRowVar, userLambda,
                Context.NONE);
    }

    private Substitution substitution(ClassSource cs, Pipelines.Materialized m,
                                      Map<String, Substitution.AssocSub> assocs,
                                      Set<String> assocEnds,
                                      Map<String, Substitution.ExistsSub> existsSubs,
                                      Map<TypedSpec, Substitution.AggRead> aggReads,
                                      Map<TypedSpec, Substitution.InQueryRead> inQueryReads,
                                      boolean filterPosition,
                                      String freshRowVar, TypedLambda userLambda,
                                      Context context) {
        assocs = context.isNone() ? assocs
                : CastNav.withWholeSourceCasts(sources, cs, assocs,
                        fqn -> sources.get(dispatch(context, fqn), fqn,
                                (t9, ex9) -> sources.dispatch(
                                        context.explicitMapping(),
                                        context.runtimeFqn(),
                                        context.chainMappings(), t9, ex9),
                                contextKey(context)));
        return new Substitution(new Substitution.Target(
                new Substitution.RowScope(userLambda.parameters().get(0),
                        freshRowVar, cs.classFqn(), cs.mappingFqn(),
                        cs.rowVar(), cs.bindings(),
                        (Type.RelationType)
                                m.pipeline().info().type(),
                        m.stripped(), m.slotPrefixes(),
                        temporal.milestoneColumnsOf(cs.pipeline(),
                                cs.classFqn())),
                new Substitution.Registries(assocs, assocEnds, existsSubs,
                        aggReads, inQueryReads, isNotEmptyCallee(), equalCallee(),
                        RelationalRootForm.primaryKeyColumns(cs.classFqn(),
                                m.pipeline(), cs.mappingFqn(), ctx),
                        inCallee(), boolCallee("and"), boolCallee("or")),
                new Substitution.TemporalView(temporal.root().legacyDates(),
                        temporal.headTemporalDates(), temporal.root(),
                        temporal.forEachDateColumn()),
                filterPosition, false));
    }

    private com.legend.compiler.element.TypedFunction boolCallee(String n2) {
        return ctx.findFunction("meta::pure::functions::boolean::" + n2).get(0);
    }

    /** The 2-arg in overload — the objectReferenceIn pk membership. */
    private com.legend.compiler.element.TypedFunction inCallee() {
        return ctx.findFunction("meta::pure::functions::collection::in")
                .stream().filter(f -> f.parameters().size() == 2)
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "resolver bug: no in registration"));
    }

    /** Any registered equal overload — membership-crossing emission. */
    private @com.legend.Nullable TypedFunction equalCallee() {
        // exact FQN (audit 23 A3): a user-defined 'equal' must never
        // become the membership callee
        var fns = ctx.findFunction("meta::pure::functions::boolean::equal");
        return fns.isEmpty() ? null : fns.get(0);
    }

    /** Any registered isNotEmpty overload — the lowerer dispatches by family. */
    private @com.legend.Nullable TypedFunction isNotEmptyCallee() {
        var fns = ctx.findFunction(
                "meta::pure::functions::collection::isNotEmpty");
        if (fns.isEmpty()) {
            throw new IllegalStateException("resolver bug: no isNotEmpty registration");
        }
        return fns.get(0);
    }

    /** ONE from()-scope entry: re-scoped Context + JSON source frames
     * (XStore §1) seeded into ClassSources' unmapped-class route. */
    private Context fromContext(TypedFrom fr, Context outer) {
        return JsonSourceFrame.fromContext(fr, outer, sources, letBindings);
    }

    /** Per-class dispatch: the runtime candidate that BINDS the class wins
     * (chain-aware — ClassSources owns the binding logic). */
    /** The memo key of a context-dependent resolution (audit 23: runtime
     * + chain mappings participate — a mixed read poisoned the cache
     * across an in-chain from()). */
    private static String contextKey(Context c) {
        return (c.explicitMapping() == null ? "" : c.explicitMapping())
                + '\u0000'
                + (c.runtimeFqn() == null ? "" : c.runtimeFqn())
                + (c.chainMappings().isEmpty() ? ""
                        : '\u0000' + String.join(",", c.chainMappings()));
    }

    private String dispatch(Context context, String classFqn) {
        return sources.dispatch(context.explicitMapping(),
                context.runtimeFqn(), context.chainMappings(), classFqn);
    }
}
