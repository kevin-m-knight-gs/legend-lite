// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedAggCol;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedDrop;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedFuncCol;
import com.legend.compiler.spec.typed.TypedGroupBy;
import com.legend.compiler.spec.typed.TypedIf;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedLimit;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedMilestonedAccess;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedProject;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedSortBy;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
/**
 * SYNTHETIC HEAD identities — filtered navigations lift to
 * {@code head#fN} chains (predicate parked for the join target),
 * two-dates-per-head splits mint {@code head#dN} (a separate join
 * identity per distinct date-set), and {@link #realHead} keeps every
 * model lookup transparent. Append-only across nested resolutions —
 * names are counter-unique; the registry is the ONE owner of the
 * '#'-suffix convention — {@link JoinIdentity} is the value type, the
 * string form exists only because heads travel as property names.
 */
final class SyntheticHeads {

    /** Function catalog — the AND-merge of stacked filter predicates
     * needs the one 2-arg {@code boolean::and} overload. */
    private final com.legend.compiler.element.ModelContext ctx;

    SyntheticHeads(com.legend.compiler.element.ModelContext ctx) {
        this.ctx = java.util.Objects.requireNonNull(ctx, "ctx");
    }

    /**
     * A join identity parsed from a head name. IDENTIFIER property names
     * cannot contain {@code '#'}; QUOTED property names (M3
     * {@code propertyName: (identifier | STRING)}) can — minting over one
     * throws loudly (the constructor guard), and a malformed suffix is a
     * loud resolver bug. RESIDUAL (documented, corpus-free): a quoted
     * property spelled exactly like a minted name ({@code 'emp#f0'})
     * decodes as synthetic — full closure needs registry-membership
     * decode. ALL encode/decode knowledge of the {@code #fN}/{@code #dN}/
     * {@code #cN} convention lives in this record.
     */
    record JoinIdentity(String prop, Kind kind, int seq) {
        enum Kind { PLAIN, FILTERED, DATED, CONCAT, POSITIONAL }

        JoinIdentity {
            if (prop.indexOf('#') >= 0) {
                // QUOTED pure property names are arbitrary strings (M3:
                // propertyName: (identifier | STRING)) — a real property
                // containing '#' must never silently masquerade as one of
                // our synthetic identities, and composed synthetics
                // (prop#cN#dM) are a resolver bug either way. LOUD.
                throw new IllegalStateException(
                        "synthetic-head identity over a property containing"
                                + " '#' (quoted-name property or composed"
                                + " synthetic — resolver bug): " + prop);
            }
        }

        static JoinIdentity of(String head) {
            int i = head.indexOf('#');
            if (i < 0) {
                return new JoinIdentity(head, Kind.PLAIN, -1);
            }
            char k = head.charAt(i + 1);
            Kind kind = switch (k) {
                case 'f' -> Kind.FILTERED;
                case 'd' -> Kind.DATED;
                case 'c' -> Kind.CONCAT;
                case 'p' -> Kind.POSITIONAL;
                default -> throw new IllegalStateException(
                        "malformed synthetic head (resolver bug): " + head);
            };
            int seq;
            try {
                seq = Integer.parseInt(head.substring(i + 2));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "malformed synthetic head (resolver bug): " + head);
            }
            return new JoinIdentity(head.substring(0, i), kind, seq);
        }

        String encoded() {
            return switch (kind) {
                case PLAIN -> prop;
                case FILTERED -> prop + "#f" + seq;
                case DATED -> prop + "#d" + seq;
                case CONCAT -> prop + "#c" + seq;
                case POSITIONAL -> prop + "#p" + seq;
            };
        }
    }

    /** The head names a filter-lifted chain ({@code #fN}). */
    static boolean isFiltered(String head) {
        return JoinIdentity.of(head).kind() == JoinIdentity.Kind.FILTERED;
    }

    @com.legend.Nullable TypedLambda pred(String head) {
        return preds.get(head);
    }

    boolean hasPred(String head) {
        return preds.containsKey(head) || branchPreds.containsKey(head)
                || corrPreds.containsKey(head) || positional.containsKey(head);
    }

    /** The positional pick parked on {@code head} ({@code #pN}), or null. */
    @com.legend.Nullable Integer positionalPick(String head) {
        return positional.get(head);
    }

    /** The synthetic identity for a POSITIONAL pick over a to-many
     * navigation ({@code $t.columns->at(k)}): reused for an equal
     * (property, k) pair. Materialization filters the target to the row
     * whose store ORDINAL is k — an ordered collection's position IS data
     * (the metamodel store seeds Table.columns' declaration order); a
     * target without an ordinal walls loudly (an unordered navigation has
     * no k-th row). */
    String parkPositional(String prop, int k) {
        for (var e : positional.entrySet()) {
            if (realHead(e.getKey()).equals(prop) && e.getValue() == k) {
                return e.getKey();
            }
        }
        String synth = new JoinIdentity(prop, JoinIdentity.Kind.POSITIONAL, count++).encoded();
        positional.put(synth, k);
        return synth;
    }

    /** The k-th row of a target pipeline by the store's ORDINAL column. */
    private TypedSpec positionalRows(TypedSpec pipe, int k) {
        Type.RelationType row = Type.requireRelationSchema(pipe.info().type());
        String ord = com.legend.builtin.SystemMetamodel.ORDINAL_COLUMN;
        boolean ordered = row.columns().stream().anyMatch(c -> c.name().equals(ord));
        if (!ordered) {
            throw new com.legend.error.NotImplementedException(
                    "positional pick (at/first) over an UNORDERED navigation is not"
                    + " supported yet — only the metamodel store's ordered collections"
                    + " carry a row ordinal");
        }
        String r = "_pos";
        var one = Multiplicity.Bounded.ONE;
        TypedSpec rv = new com.legend.compiler.spec.typed.TypedVariable(r, new ExprType(row, one));
        TypedSpec read = new TypedPropertyAccess(rv, ord,
                new ExprType(Type.Primitive.INTEGER, Multiplicity.Bounded.ZERO_ONE));
        var eq = ctx.findFunction("meta::pure::functions::boolean::equal").stream()
                .filter(f -> f.parameters().size() == 2).findFirst().orElseThrow();
        TypedSpec cond = new com.legend.compiler.spec.typed.TypedNativeCall(eq, List.of(read,
                new com.legend.compiler.spec.typed.TypedCInteger((long) k,
                        new ExprType(Type.Primitive.INTEGER, one))),
                new ExprType(Type.Primitive.BOOLEAN, one));
        TypedLambda pred = new TypedLambda(List.of(r), List.of(cond),
                new ExprType(new Type.FunctionType(List.of(new Type.Param(row, one)),
                        new Type.Param(Type.Primitive.BOOLEAN, one)), one));
        return new com.legend.compiler.spec.typed.TypedFilter(pipe, pred, pipe.info());
    }

    /** The CORRELATED predicate parked on {@code head}, or null. */
    @com.legend.Nullable TypedLambda correlatedPred(String head) {
        return corrPreds.get(head);
    }

    /** ALL parked correlated predicates — the demand scan reads their
     * OUTER-variable paths as PARENT demand (#69: the lift moved the
     * only occurrence of the read out of the projection column). */
    java.util.Collection<TypedLambda> allCorrelatedPreds() {
        return corrPreds.values();
    }

    /** ALL predicates parked on a head: singleton for a {@code #fN} head,
     * the non-null branch predicates for a {@code #cN} head, empty
     * otherwise. Demand/tail scans iterate this — every branch's reads
     * pull the target's slots exactly like a single lifted predicate. */
    List<TypedLambda> allPreds(String head) {
        TypedLambda single = preds.get(head);
        if (single == null) {
            single = corrPreds.get(head);
        }
        if (single != null) {
            return List.of(single);
        }
        List<TypedLambda> branches = branchPreds.get(head);
        if (branches != null) {
            return branches.stream().filter(java.util.Objects::nonNull).toList();
        }
        return List.of();
    }

    /**
     * Apply a head's parked filter material to its finished target
     * pipeline: a {@code #fN} head filters once; a {@code #cN} head maps
     * each branch (a null branch predicate = the unfiltered stream) and
     * UNION-ALLs the branch pipes (engine: concatenated navigation
     * streams join as one union subselect). PLAIN/DATED heads pass
     * through.
     */
    TypedSpec applyToPipe(String head, TypedSpec pipe,
            java.util.function.BiFunction<TypedSpec, TypedLambda, TypedSpec> filter) {
        Integer k = positional.get(head);
        if (k != null) {
            return positionalRows(pipe, k);
        }
        TypedLambda single = preds.get(head);
        if (single != null) {
            return filter.apply(pipe, single);
        }
        List<TypedLambda> branches = branchPreds.get(head);
        if (branches == null || branches.isEmpty()) {
            // empty branch list = nothing parked (registration never
            // stores an empty list; the old code returned NULL here)
            return pipe;
        }
        TypedLambda b0 = branches.get(0);
        TypedSpec out = b0 == null ? pipe : filter.apply(pipe, b0);
        for (TypedLambda b : branches.subList(1, branches.size())) {
            TypedSpec member = b == null ? pipe : filter.apply(pipe, b);
            out = new com.legend.compiler.spec.typed.TypedConcatenate(
                    out, member, member.info());
        }
        return out;
    }

    /** A fresh date-fingerprinted identity for {@code prop}. */
    String mintDateName(String prop) {
        return new JoinIdentity(prop, JoinIdentity.Kind.DATED, count++).encoded();
    }

    /** A fresh filter-lifted identity for {@code prop}. */

    /** The synthetic identity for a (property, predicate) pair — REUSED
     * when an EQUAL predicate is already parked on the same real head
     * (engine merge-by-identity: employeesByCityOrManager('Hoboken','Bla')
     * twice plus its inline spelling share ONE subselect; per-call-site
     * minting over-fragments and cross-multiplies projection rows — the
     * Fork golden's 3 joins for 5 columns). Structural record equality;
     * alpha-variant spellings stay separate (safe over-fragmentation). */
    private String parkFiltered(String prop, TypedLambda pred) {
        return parkFiltered(prop, pred, false);
    }

    /** {@code valuePosition}: the head joins ROW-DROPPING (INNER — §4AD
     * P1 placement bit). Identity FORKS by placement class: a value
     * occurrence never shares a projection occurrence's join copy (one
     * join cannot be both INNER and LEFT); equal (prop, pred, placement)
     * still share one identity. */
    private String parkFiltered(String prop, TypedLambda pred,
            boolean valuePosition) {
        boolean closed = predClosedOverParam(pred);
        java.util.Map<String, TypedLambda> pool = closed ? preds : corrPreds;
        TypedSpec canon = alphaCanonicalBody(pred);
        for (var e : pool.entrySet()) {
            if (realHead(e.getKey()).equals(prop)
                    && alphaCanonicalBody(e.getValue()).equals(canon)
                    && innerValueHeads.contains(e.getKey()) == valuePosition) {
                return e.getKey();
            }
        }
        String synth = mintFilteredName(prop);
        pool.put(synth, pred);
        if (valuePosition) {
            innerValueHeads.add(synth);
        }
        return synth;
    }

    /** Heads whose join is ROW-DROPPING (INNER): value-position lifts.
     * Consumed at the AssocJoin construction sites (the join-kind fact
     * rides the AssocJoin, emission reads it — never re-derived). */
    private final Set<String> innerValueHeads = new java.util.LinkedHashSet<>();

    boolean isInnerValueHead(String head) {
        return innerValueHeads.contains(head);
    }

    /** Batch 69b: a correlated filter predicate parked on a hop at index
     * &ge; {@code from} of a nav-slot chain has NO application site — the
     * parent-copy reroute's tail loop applies head and first-tail-hop
     * predicates only, and the slot spine never parks a sub-hop
     * correlated pred in-target (isolationTest:
     * {@code employees.group.children->filter(c | ... == $x.employees
     * .product.name)} answered with EVERY child; the chain could not
     * reroute because a plain path had already demanded its parent
     * alias). Wall loudly — a wrong answer is never a gap. */
    void unappliedCorrelatedWall(List<String> path, int from) {
        for (int hi = from; hi < path.size(); hi++) {
            if (correlatedPred(path.get(hi)) != null) {
                throw new com.legend.error.NotImplementedException(
                        "correlated filter predicate on hop '"
                        + realHead(path.get(hi))
                        + "' at depth " + (hi + 1) + " of the navigation "
                        + String.join(".", path.stream()
                                .map(SyntheticHeads::realHead).toList())
                        + " has no application site yet (the parent-copy"
                        + " reroute applies head and first-tail-hop"
                        + " predicates only)");
            }
        }
    }

    /** The predicate body with its binder renamed to a canonical name —
     * the inliner alpha-freshens per call site (e, e_1, e_2 under an
     * outer shadowing scope), which defeated plain record equality (the
     * OffsetExplosion probe: 5 subselects for 2 distinct preds). */
    private static TypedSpec alphaCanonicalBody(TypedLambda pred) {
        // audit 23 B5: a shape this canonicalization cannot handle must be
        // LOUD, not silently un-canonicalized — an un-merged identity
        // cross-multiplies projection rows (the Merge golden: 7 vs 13)
        if (pred.parameters().size() != 1 || pred.body().size() != 1) {
            throw new com.legend.error.NotImplementedException(
                    "filtered-navigation predicate"
                    + " with " + pred.parameters().size() + " parameter(s)/"
                    + pred.body().size() + " statement(s) cannot join the"
                    + " merge-by-identity registry yet");
        }
        String param = pred.parameters().get(0);
        Type.FunctionType pft = pred.functionType();
        var pInfo = pft.params().size() == 1
                ? new ExprType(pft.params().get(0).type(),
                        pft.params().get(0).multiplicity())
                : null;
        if (pInfo == null) {
            throw new IllegalStateException("resolver bug: filtered-nav"
                    + " predicate info is not a 1-param FunctionType — the"
                    + " canonical binder cannot be typed");
        }
        // the canonical binder is COMPARISON-ONLY (never emitted): the
        // NUL-prefixed name cannot collide with any parseable pure
        // identifier (audit 23 — a user var literally named _canon was
        // capturable)
        return Substitution.inlineParam(pred.body().get(0), param,
                new com.legend.compiler.spec.typed.TypedVariable(
                        "\u0000canon", pInfo));
    }

    private String mintFilteredName(String prop) {
        return new JoinIdentity(prop, JoinIdentity.Kind.FILTERED, count++).encoded();
    }

    /** A fresh concatenated-stream identity for {@code prop}. */
    private String mintConcatName(String prop) {
        return new JoinIdentity(prop, JoinIdentity.Kind.CONCAT, count++).encoded();
    }

    /** Scan entry: the lambda's BODY under its own parameter (never the lambda node). */
    /**
     * PRE-REWRITE: a filtered navigation consumed as a BARE COLLECTION —
     * {@code $o.head(%d)->filter(f).leaf} with non-scalar multiplicity —
     * lifts into a SYNTHETIC head {@code head#fN}: a plain 2-hop chain
     * whose association-join TARGET pipeline carries the substituted
     * predicate (engine parity: the chain filter parks INSIDE the
     * navigation's join-tree node; the LEFT join row-explodes and
     * delivers NULL — TDSNull — on no surviving match). Scalar
     * ({@code [0..1]}) bare reads stay with the correlated-scalar arm
     * ({@code filteredNavLeafRead}) — the split is exactly complementary.
     * The walk is BEST-EFFORT: unknown node kinds pass through unchanged,
     * so an unlifted shape keeps today's loud not-substitutable error —
     * never silent SQL.
     */
    TypedSpec liftFilteredHeads(TypedSpec n) {
        return liftFilteredHeads(n, true);
    }


    /** Node-local canonicalizer applied before the lift arms (identity by
     * default) — the resolver wires the subType-cast rewrite here so a
     * witness-bearing cast becomes the filtered-nav shape THIS pass
     * already lifts (per-cast join identity via parkFiltered). */
    private java.util.function.UnaryOperator<TypedSpec> canon =
            java.util.function.UnaryOperator.identity();

    void setCanonicalizer(java.util.function.UnaryOperator<TypedSpec> c) {
        canon = c;
    }

    /** §4AD P2: filter-position conjoin channel. Inside a TypedFilter
     * predicate, a lifted filtered read parks BARE and its β-inlined
     * qualifier predicate joins {@code pending}; the wrapper attaches
     * pending conjuncts at the NEAREST boolean ancestor — the consuming
     * comparison — reproducing the engine's per-disjunct
     * (qual-pred AND cmp) grouping (testQualifierQueryWithOr cell)
     * structurally, for every operator family. */
    private record FilterCtx(List<TypedSpec> pending) {
        FilterCtx() {
            this(new java.util.ArrayList<>());
        }
    }

    private TypedSpec liftFilteredHeads(TypedSpec n, boolean enabled) {
        return liftFilteredHeads(n, enabled, null);
    }

    private TypedSpec liftFilteredHeads(TypedSpec n, boolean enabled,
            @com.legend.Nullable FilterCtx fc) {
        TypedSpec r = liftArms(n, enabled, fc);
        if (fc != null && !fc.pending().isEmpty()
                && r.info().type() == Type.Primitive.BOOLEAN
                && r.info().multiplicity()
                        instanceof Multiplicity.Bounded mb1
                && Integer.valueOf(1).equals(mb1.upper())
                && Integer.valueOf(1).equals(mb1.lower())) {
            for (int i = fc.pending().size() - 1; i >= 0; i--) {
                r = andExpr(fc.pending().get(i), r);
            }
            fc.pending().clear();
        }
        return r;
    }

    /** {@code a && b} at EXPRESSION level (andMerge's lambda-less twin). */
    private TypedSpec andExpr(TypedSpec a, TypedSpec b) {
        var fns = ctx.findFunction("meta::pure::functions::boolean::and")
                .stream().filter(f -> f.parameters().size() == 2).toList();
        if (fns.size() != 1) {
            throw new IllegalStateException("resolver bug: expected exactly"
                    + " one 2-arg boolean::and, found " + fns.size());
        }
        return new TypedNativeCall(fns.get(0), List.of(a, b), b.info());
    }

    private TypedSpec liftArms(TypedSpec n, boolean enabled,
            @com.legend.Nullable FilterCtx fc) {
        if (enabled) {
            n = canon.apply(n);
        }
        // ->map(e|$e.leaf) over a (filtered) class navigation IS the
        // property-path spelling — normalize and take the lift arm (the
        // qualifier-inlined aggregate shape:
        // joinStrings(map(filter(head, pred), .leaf)); #69).
        if (enabled && n instanceof TypedMap tm
                && tm.mapper().parameters().size() == 1
                && tm.mapper().body().size() == 1
                && tm.mapper().body().get(0) instanceof TypedPropertyAccess mb
                && mb.source() instanceof com.legend.compiler.spec.typed
                        .TypedVariable mv
                && mv.name().equals(tm.mapper().parameters().get(0))
                && filterBehindToOne(tm.source()) instanceof TypedFilter
                && tm.source().info().type() instanceof Type.ClassType) {
            return liftFilteredHeads(new TypedPropertyAccess(
                    tm.source(), mb.property(), tm.info()), enabled, fc);
        }
        // sortBy over a FILTERED navigation (ordered sub-aggregation
        // source: filter(nav)->sortBy(key).leaf->joinStrings(...)): the
        // filter lifts into the synthetic filtered head exactly like the
        // leaf-read spelling; the sortBy rides on the renamed head as
        // ORDER metadata for the agg scan.
        if (enabled && n instanceof TypedSortBy sb0
                && sb0.source() instanceof TypedFilter fs
                && fs.predicate().parameters().size() == 1
                && fs.info().type() instanceof Type.ClassType
                && isLiftableNav(fs.source())) {
            TypedSpec head0 = liftFilteredHeads(fs.source(), true);
            TypedSpec renamed0;
            String synth0;
            if (head0 instanceof com.legend.compiler.spec.typed
                    .TypedMilestonedAccess ma0) {
                synth0 = parkFiltered(ma0.property(), fs.predicate());
                renamed0 = new TypedMilestonedAccess(ma0.source(), synth0,
                        ma0.dates(), ma0.sweep(), ma0.info());
            } else {
                var hp0 = (TypedPropertyAccess) head0;
                synth0 = parkFiltered(hp0.property(), fs.predicate());
                renamed0 = new TypedPropertyAccess(hp0.source(), synth0,
                        hp0.info());
            }
            return new TypedSortBy(renamed0,
                    (TypedLambda) liftFilteredHeads(sb0.key(), enabled),
                    sb0.ascending(), sb0.info());
        }
        // WRAPPED filtered-nav spellings (exists-over-filter, map-wrapped
        // or stacked-filter value reads) canonicalize to the DIRECT one
        // and re-enter the walk — foldWrappedSpelling.
        if (enabled) {
            TypedSpec folded = foldWrappedSpelling(n);
            if (folded != null) {
                return liftFilteredHeads(folded, enabled, fc);
            }
        }
        TypedSpec betaLeaf = enabled ? liftMapWrappedFilterLeaf(n) : null;
        if (betaLeaf != null) {
            return betaLeaf;
        }
        if (enabled && n instanceof TypedPropertyAccess pa) {
            TypedSpec picked = liftPositionalRead(pa);
            if (picked != null) {
                return picked;
            }
        }
        if (enabled
                && n instanceof TypedPropertyAccess pa
                && filterBehindToOne(pa.source()) instanceof TypedFilter f
                && f.predicate().parameters().size() == 1
                && f.info().type()
                        instanceof Type.ClassType
                && isLiftableNav(f.source())) {
            return liftFilteredReadArm(pa, f, fc);
        }
        // COMPUTED-mapper aggregation source (#69) —
        // map(filter(nav), λe.<computed>) where the mapper body is NOT a
        // plain property read (derived-property β-inlines: concat(...)).
        // The filtered SOURCE lifts into a synthetic head exactly like the
        // leaf-read spelling; the mapper rides along and substitutes
        // through the target's bindings at the aggregation fold.
        if (enabled && n instanceof TypedMap tm2
                && tm2.mapper().parameters().size() == 1
                && tm2.source() instanceof TypedFilter f0
                && f0.predicate().parameters().size() == 1
                && f0.info().type() instanceof Type.ClassType
                && isLiftableNav(f0.source())
                && !(tm2.info().multiplicity()
                        instanceof com.legend.compiler.element.type
                                .Multiplicity.Bounded mb2
                        && Integer.valueOf(1).equals(mb2.upper()))) {
            TypedSpec head = liftFilteredHeads(f0.source(), true);
            TypedSpec renamed;
            String synth;
            if (head instanceof com.legend.compiler.spec.typed
                    .TypedMilestonedAccess ma) {
                synth = parkFiltered(ma.property(), f0.predicate());
                renamed = new TypedMilestonedAccess(
                        ma.source(), synth, ma.dates(), ma.sweep(), ma.info());
            } else {
                var hp = (TypedPropertyAccess) head;
                synth = parkFiltered(hp.property(), f0.predicate());
                renamed = new TypedPropertyAccess(
                        hp.source(), synth, hp.info());
            }
            return new TypedMap(renamed,
                    (TypedLambda) liftFilteredHeads(tm2.mapper(), enabled),
                    tm2.info());
        }
        // BARE-AGGREGATE filtered navigation (no leaf read) — see
        // liftAggBareFilter.
        if (enabled && n instanceof TypedNativeCall agg) {
            TypedSpec lifted = liftAggBareFilter(agg, enabled);
            if (lifted != null) {
                return lifted;
            }
        }
        TypedSpec ccLift = enabled ? liftConcatArm(n) : null;
        if (ccLift != null) {
            return ccLift;
        }
        return descend(n, enabled, fc);
    }

    /** The structural descent: every node kind the lift walks through,
     * rebuilt with lifted children (unknown kinds pass unchanged). */
    private TypedSpec descend(TypedSpec n, boolean enabled,
            @com.legend.Nullable FilterCtx fc) {
        return switch (n) {
            case TypedProject p ->
                    new TypedProject(
                            liftFilteredHeads(p.source(), enabled),
                            p.columns().stream().map(c ->
                                    new TypedFuncCol(
                                            c.name(),
                                            (TypedLambda) liftFilteredHeads(c.fn(),
                                                    enabled && !valuesLambdas
                                                            .contains(c.fn()))))
                                    .toList(),
                            p.info());
            case TypedFilter f -> {
                // each predicate STATEMENT gets its own conjoin scope;
                // the statement root is Boolean[1], so the wrapper
                // attaches any conjunct the walk left pending
                FilterCtx pfc = new FilterCtx();
                TypedLambda p0 = f.predicate();
                TypedLambda p2 = new TypedLambda(p0.parameters(),
                        p0.body().stream()
                                .map(b -> liftFilteredHeads(b, enabled, pfc))
                                .toList(),
                        p0.info());
                if (!pfc.pending().isEmpty()) {
                    throw new IllegalStateException("resolver bug: "
                            + pfc.pending().size() + " filter-position"
                            + " conjunct(s) never attached to a boolean"
                            + " consumption");
                }
                yield new TypedFilter(
                        liftFilteredHeads(f.source(), enabled),
                        p2, f.info());
            }
            case TypedSortBy sb -> new TypedSortBy(
                    liftFilteredHeads(sb.source(), enabled),
                    (TypedLambda) liftFilteredHeads(sb.key(), enabled),
                    sb.ascending(), sb.keyAlias(), sb.info());
            case TypedLimit l -> new TypedLimit(
                    liftFilteredHeads(l.source(), enabled), l.count(), l.info());
            case TypedDrop d -> new TypedDrop(
                    liftFilteredHeads(d.source(), enabled), d.count(), d.info());
            case TypedSlice sl -> new TypedSlice(
                    liftFilteredHeads(sl.source(), enabled),
                    sl.start(), sl.stop(), sl.info());
            case TypedFrom fr -> new TypedFrom(
                    liftFilteredHeads(fr.source(), enabled),
                    fr.mapping(), fr.runtime(), fr.chainMappings(),
                    fr.jsonSources(), fr.sqlSetups(), fr.csvSetups(), fr.connectionName(),
                    fr.info());
            case TypedLambda l -> new TypedLambda(l.parameters(),
                    l.body().stream().map(b -> liftFilteredHeads(b, enabled))
                            .toList(), l.info());
            // AGGREGATION arguments suspend the filter-conjoin channel
            // (aggregated reads ride the GROUPED route — pred-in-
            // subselect is the measured cell; a conjunct would widen
            // the boolean leaf across agg heads: the validation
            // milestoning-aggregation trio walled on exactly that).
            // NEGATION suspends it too: the to-many negation arm
            // transcribes the engine's null-compensation for equal/in
            // — not(and(guard, cmp)) is outside its vocabulary
            // (validateComplexValidation6 walled). Negated-consumption
            // pad behavior stays the batch-7 residue (ledgered).
            case TypedNativeCall c ->
                    c.withChildren(c.args().stream()
                            .map(a -> liftFilteredHeads(a, enabled,
                                    CorrelatedSubselects.isAggregate(c)
                                            || "meta::pure::functions::boolean::not"
                                                    .equals(c.callee()
                                                            .qualifiedName())
                                            ? null : fc))
                                    .toList());
            case TypedPropertyAccess pa ->
                    new TypedPropertyAccess(
                            liftFilteredHeads(pa.source(), enabled),
                            pa.property(), pa.info());
            case TypedMilestonedAccess ma ->
                    new TypedMilestonedAccess(
                            liftFilteredHeads(ma.source(), enabled), ma.property(),
                            ma.dates(), ma.sweep(), ma.info());
            // auto-map mapper bodies are VALUE flattenings (empties drop) —
            // the TDS lift stays off inside them; unlifted shapes keep
            // their loud error
            case TypedMap m ->
                    new TypedMap(
                            liftFilteredHeads(m.source(), enabled),
                            (TypedLambda) liftFilteredHeads(m.mapper(), false),
                            m.info());
            case TypedIf i ->
                    new TypedIf(
                            liftFilteredHeads(i.condition(), enabled, fc),
                            liftFilteredHeads(i.thenBranch(), enabled, fc),
                            i.elseBranch().map(e ->
                                    liftFilteredHeads(e, enabled, fc)),
                            i.info());
            case TypedCollection c ->
                    new TypedCollection(
                            c.elements().stream().map(e ->
                                    liftFilteredHeads(e, enabled, fc)).toList(),
                            c.info());
            case TypedCast c ->
                    new TypedCast(
                            liftFilteredHeads(c.source(), enabled, fc),
                            c.target(), c.info(), c.wire());
            // A constructed instance is a VALUE node like a collection: the
            // lift reaches into every field (the map-over-row form's
            // ^Inst(... $r.columns->at(0).name ...) body).
            case com.legend.compiler.spec.typed.TypedNewInstance ni -> {
                java.util.Map<String, TypedSpec> ps = new java.util.LinkedHashMap<>();
                ni.properties().forEach((k, v) ->
                        ps.put(k, liftFilteredHeads(v, enabled, fc)));
                yield new com.legend.compiler.spec.typed.TypedNewInstance(ni.classFqn(), ps, ni.info());
            }
            case TypedGroupBy gb ->
                    new TypedGroupBy(
                            liftFilteredHeads(gb.source(), enabled),
                            gb.keys().stream().map(k ->
                                    new TypedGroupBy.GroupKey(k.column(),
                                            k.fn().map(fn -> (TypedLambda)
                                                    liftFilteredHeads(fn,
                                                            enabled))))
                                    .toList(),
                            gb.aggs().stream().map(a ->
                                    new TypedAggCol(a.name(), (TypedLambda)
                                            liftFilteredHeads(a.map(), enabled),
                                            a.reduce(),
                                            a.orderKey() == null ? null
                                                    : (TypedLambda) liftFilteredHeads(
                                                            a.orderKey(), enabled),
                                            a.orderAsc()))
                                    .toList(),
                            gb.info());
            default -> n;
        };
    }

    /**
     * BARE-AGGREGATE filtered navigation (no leaf read):
     * count(filter($p.firm->toOne().employees, pred)) — the filter is the
     * DIRECT collection argument of an aggregate call (engine:
     * employeesByAge(30)->count() groups the filtered chained hop in a
     * parent-keyed subselect). The filter lifts into a synthetic filtered
     * head exactly like the leaf-read spelling; CONTEXT-GATED to the
     * aggregate-argument position — a global bare-filter arm hijacks
     * exists-over-filter and correlated shapes owned by other routes (the
     * reverted -22 regression). Null when the arm does not apply.
     */
    /** THE filtered-read arm (§4AD batches 5+7 + P2): every
     * filtered-nav read takes the fan-out route, ALL positions and
     * multiplicities (charter decisions 1-2; batch-0 placement table —
     * no position gates). CLOSED predicates park on the target
     * pipeline; a predicate reading the OUTER row parks CORRELATED
     * (applied at the join condition); EQUAL preds on one head REUSE
     * one identity (parkFiltered). §4AD P2 — FILTER position: the
     * IN-TARGET park stays (the engine's own emission for these
     * shapes is pred-in-ON — nestedFilterFunctionExpressionWithOr-
     * Condition golden — and its fan counts are the measured rows),
     * PLUS the inlined qualifier pred conjoins the consuming
     * comparison via the pending channel: REDUNDANT over matched
     * rows, the PAD GUARD over unmatched ones — a pad row's NULL
     * reads could otherwise satisfy a null-safe comparison the
     * qual-pred should have guarded (both engine forms drop that
     * row — ValueMapPlacementTest.filterPositionGroupsQualPredWithCmp). */
    /** A leaf read through a POSITIONAL pick over a bare to-many
     * navigation head — {@code $t.columns->at(k)[->cast(@C)].name} — lifts
     * into the synthetic head {@code columns#pN} (a to-one read: the k-th
     * row by the store ordinal); null for any other shape. */
    private @com.legend.Nullable TypedSpec liftPositionalRead(TypedPropertyAccess pa) {
        TypedSpec src = pa.source();
        Type castTo = null;
        while (true) {
            if (src instanceof com.legend.compiler.spec.typed.TypedCast tc) {
                castTo = castTo == null ? tc.target() : castTo;
                src = tc.source();
                continue;
            }
            if (src instanceof com.legend.compiler.spec.typed.TypedNativeCall c
                    && c.args().size() == 1
                    && com.legend.builtin.Pure.isToOneCall(c.callee().qualifiedName())) {
                src = c.args().get(0);
                continue;
            }
            break;
        }
        if (!(src instanceof com.legend.compiler.spec.typed.TypedNativeCall at)
                || !Anchors.isStaticAt(at)
                || !(at.args().get(0) instanceof TypedPropertyAccess nav)
                || !isLiftableNav(nav)
                || !(nav.info().type() instanceof Type.ClassType)
                || !(nav.info().multiplicity() instanceof Multiplicity.Bounded nb) || !nb.isMany()) {
            return null;
        }
        int k = (int) ((com.legend.compiler.spec.typed.TypedCInteger) at.args().get(1)).value().longValue();
        Type headType = castTo instanceof Type.ClassType ? castTo : nav.info().type();
        TypedSpec renamed = new TypedPropertyAccess(nav.source(),
                parkPositional(nav.property(), k),
                new ExprType(headType, Multiplicity.Bounded.ZERO_ONE));
        return new TypedPropertyAccess(renamed, pa.property(), pa.info());
    }

    private TypedSpec liftFilteredReadArm(TypedPropertyAccess pa,
            TypedFilter f, @com.legend.Nullable FilterCtx fc) {
        if (fc != null && f.predicate().body().size() == 1) {
            TypedSpec headF = liftFilteredHeads(f.source(), true, fc);
            TypedSpec renamedF;
            if (headF instanceof TypedMilestonedAccess maF) {
                renamedF = new TypedMilestonedAccess(maF.source(),
                        parkFiltered(maF.property(), f.predicate()),
                        maF.dates(), maF.sweep(), maF.info());
            } else {
                var hpF = (TypedPropertyAccess) headF;
                renamedF = new TypedPropertyAccess(hpF.source(),
                        parkFiltered(hpF.property(), f.predicate()),
                        hpF.info());
            }
            fc.pending().add(Substitution.inlineParam(
                    f.predicate().body().get(0),
                    f.predicate().parameters().get(0), renamedF));
            return new TypedPropertyAccess(renamedF, pa.property(),
                    pa.info());
        }
        TypedSpec head = liftFilteredHeads(f.source(), true);
        TypedSpec renamed;
        if (head instanceof com.legend.compiler.spec.typed
                .TypedMilestonedAccess ma) {
            renamed = new TypedMilestonedAccess(ma.source(),
                    parkFiltered(ma.property(), f.predicate()),
                    ma.dates(), ma.sweep(), ma.info());
        } else {
            var hp = (TypedPropertyAccess) head;
            renamed = new TypedPropertyAccess(hp.source(),
                    parkFiltered(hp.property(), f.predicate()), hp.info());
        }
        return new TypedPropertyAccess(renamed, pa.property(), pa.info());
    }

    private @com.legend.Nullable TypedSpec liftAggBareFilter(
            TypedNativeCall agg, boolean enabled) {
        if (agg.args().isEmpty()
                || !CorrelatedSubselects.isAggregate(agg)
                || !(agg.args().get(0) instanceof TypedFilter fa)
                || fa.predicate().parameters().size() != 1
                || !(fa.info().type() instanceof Type.ClassType)
                || !isLiftableNav(fa.source())
                || (fa.info().multiplicity()
                        instanceof Multiplicity.Bounded ab
                        && Integer.valueOf(1).equals(ab.upper()))) {
            return null;
        }
        TypedSpec head = liftFilteredHeads(fa.source(), true);
        TypedSpec renamed;
        String synth;
        if (head instanceof TypedMilestonedAccess ma) {
            synth = parkFiltered(ma.property(), fa.predicate());
            renamed = new TypedMilestonedAccess(
                    ma.source(), synth, ma.dates(), ma.sweep(), ma.info());
        } else {
            var hp = (TypedPropertyAccess) head;
            synth = parkFiltered(hp.property(), fa.predicate());
            renamed = new TypedPropertyAccess(
                    hp.source(), synth, hp.info());
        }
        List<TypedSpec> newArgs = new java.util.ArrayList<>(agg.args());
        newArgs.set(0, renamed);
        for (int i = 1; i < newArgs.size(); i++) {
            newArgs.set(i, liftFilteredHeads(newArgs.get(i), enabled));
        }
        return agg.withChildren(newArgs);
    }

    /**
     * VALUES-position filtered navigation (map terminal): pure flattening
     * DROPS empties here, so the predicate parks in the OUTER where
     * (engine golden: plain LEFT JOIN + WHERE — non-matching parents
     * contribute nothing, never a NULL value). The head still lifts to a
     * synthetic chain for join identity, but WITHOUT the in-target
     * predicate; the predicate joins the chain as an injected
     * object-space filter whose reads inline through the synthetic head.
     */
    TypedMap liftValueMapFilter(
            TypedMap m) {
        TypedLambda mapper = m.mapper();
        if (mapper.parameters().size() != 1) {
            return m;
        }
        // §4AD P1 (placement addendum §6): COMPUTED mapper bodies walk
        // too — the old single-plain-read guard was the defect boundary
        // (computed bodies fell through to the PROJECT route and
        // inherited its row-PRESERVING placement; null-skipping
        // operators then minted phantom values —
        // testQualifierWithOperation). Value heads park their predicate
        // IN-TARGET like every position (one mechanism) and differ ONLY
        // by join kind: INNER, the row-dropping placement bit. INNER
        // beats the engine's LEFT+top-WHERE hoist on the unmeasured
        // null-safe-pred cell (a hoisted null-safe pred is TRUE over
        // the LEFT pad row and mints phantoms —
        // ValueMapPlacementTest.doubleNullConjunctRuleParity caught it);
        // with INNER the pad row never exists. Row-identical to the
        // engine's emission on every measured cell.
        boolean[] lifted = {false};
        List<TypedSpec> body2 = new java.util.ArrayList<>(mapper.body().size());
        for (TypedSpec b : mapper.body()) {
            body2.add(liftValueRead(b, mapper, lifted));
        }
        if (!lifted[0]) {
            return m;
        }
        TypedLambda mapper2 = new TypedLambda(mapper.parameters(), body2,
                mapper.info());
        valuesLambdas.add(mapper2);
        return new TypedMap(m.source(), mapper2, m.info());
    }

    /** One VALUE-position filtered read — {@code $p.prop->filter(pred)
     * .leaf} anywhere in the mapper body (computed bodies recurse;
     * NESTED lambdas keep their own routes — their binders are not the
     * mapper's row). toOne/first/head conformance wrappers are
     * SQL-erased (charter decision 1, same policy as the projection arm
     * and liftConcatStreams) — this arm IS the task-#72 retirement path
     * for value position. Multi-occurrence: equal preds share one
     * identity, different preds fork copies (engine golden
     * testTwoQualifiersWithOperation: persontable_0 vs persontable_2);
     * every occurrence's INNER join must match — the measured
     * ALL-preds-AND-one-WHERE row behavior, by composition. */
    private TypedSpec liftValueRead(TypedSpec n, TypedLambda mapper,
            boolean[] lifted) {
        if (n instanceof TypedLambda) {
            return n;
        }
        if (n instanceof TypedPropertyAccess pa
                && filterBehindToOne(pa.source()) instanceof TypedFilter f
                && f.predicate().parameters().size() == 1
                && f.predicate().body().size() == 1
                && f.info().type() instanceof Type.ClassType
                && isLiftableNav(f.source())
                && mapper.parameters().get(0).equals(bottomVarOf(f.source()))) {
            TypedSpec renamed;
            if (f.source() instanceof com.legend.compiler.spec.typed
                    .TypedMilestonedAccess ma) {
                renamed = new TypedMilestonedAccess(
                        ma.source(),
                        parkFiltered(ma.property(), f.predicate(), true),
                        ma.dates(), ma.sweep(), ma.info());
            } else {
                var hp = (TypedPropertyAccess) f.source();
                renamed = new TypedPropertyAccess(
                        hp.source(),
                        parkFiltered(hp.property(), f.predicate(), true),
                        hp.info());
            }
            lifted[0] = true;
            return new TypedPropertyAccess(renamed, pa.property(), pa.info());
        }
        return rebuildChildren(n, c -> liftValueRead(c, mapper, lifted));
    }

    /**
     * The concat-stream lift body: flatten nested binary concatenates,
     * require every branch to be a (filtered) navigation of ONE shared
     * head property bottoming at the same receiver shape, mint the
     * {@code #cN} identity and park the branch predicates in order
     * (null = unfiltered branch). Null when any branch refuses — the
     * caller falls through to the loud wall.
     */
    private @com.legend.Nullable TypedSpec liftConcatStreams(TypedNativeCall cc,
            TypedPropertyAccess leafRead) {
        List<TypedSpec> streams = new java.util.ArrayList<>();
        flattenConcat(cc, streams);
        String prop = null;
        TypedSpec headNode = null;
        List<TypedLambda> branches = new java.util.ArrayList<>(streams.size());
        for (TypedSpec s : streams) {
            TypedSpec nav;
            TypedLambda pred;
            // conform-by-emission wrappers are SQL-erased (Scalars toOne
            // policy): a derived property declared [1] over a filtered
            // stream arrives as toOne(filter(...)) — look through
            while (s instanceof TypedNativeCall w
                    && w.args().size() == 1
                    && com.legend.builtin.Pure.isToOneCall(w.callee().qualifiedName())) {
                s = w.args().get(0);
            }
            if (s instanceof TypedFilter f
                    && f.predicate().parameters().size() == 1
                    && f.info().type() instanceof Type.ClassType
                    && isLiftableNav(f.source())
                    && predClosedOverParam(f.predicate())) {
                nav = f.source();
                pred = f.predicate();
            } else if ((s instanceof TypedPropertyAccess
                    || s instanceof TypedMilestonedAccess)
                    && s.info().type() instanceof Type.ClassType
                    && isLiftableNav(s)) {
                nav = s;
                pred = null;
            } else {
                return null;
            }
            String p = nav instanceof TypedMilestonedAccess ma
                    ? ma.property() : ((TypedPropertyAccess) nav).property();
            if (prop == null) {
                prop = p;
                headNode = nav;
            } else if (!prop.equals(p) || !nav.equals(headNode)) {
                // ONE head means one WHOLE navigation node: the property
                // AND its receiver chain AND its milestoning dates (audit
                // 16: branch 2's $p.parent hop or a different business
                // date silently vanished into branch 1's head — wrong
                // rows). Cross-head/cross-date unions are their own rung;
                // the refusal keeps the loud not-substitutable wall.
                return null;
            }
            branches.add(pred);
        }
        if (prop == null || branches.size() < 2) {
            return null;
        }
        // ONE identity per distinct stream expression: the same
        // concatenated stream in two projection columns rides ONE join
        // (engine merge-by-identity — two-column Merge golden expects 7
        // rows, two joins gave 13). The HEAD NODE is part of the identity
        // (same property over different receivers/dates is a different
        // stream); its BOTTOM VARIABLE alpha-normalizes so per-column
        // lambda param names (p| vs t|) don't split one stream into two
        // joins.
        Map<String, String> rootEnv = new LinkedHashMap<>();
        rootEnv.put(bottomVarOf(headNode), "#root");
        List<Object> memoKey = List.of(prop,
                alphaNormalize(headNode, rootEnv, new int[]{0}),
                branches.stream().map(b -> b == null ? ""
                        : (Object) canonicalPred(b)).toList());
        String synth = concatMemo.get(memoKey);
        if (synth == null) {
            synth = mintConcatName(prop);
            concatMemo.put(memoKey, synth);
            branchPreds.put(synth, branches);
        }
        TypedSpec renamed;
        if (headNode instanceof TypedMilestonedAccess ma) {
            renamed = new TypedMilestonedAccess(
                    ma.source(), synth, ma.dates(), ma.sweep(), ma.info());
        } else {
            var hp = (TypedPropertyAccess) java.util.Objects.requireNonNull(headNode, "headNode");
            renamed = new TypedPropertyAccess(
                    java.util.Objects.requireNonNull(hp, "hp").source(), synth, hp.info());
        }
        return new TypedPropertyAccess(
                renamed, leafRead.property(), leafRead.info());
    }

    private static void flattenConcat(TypedSpec n, List<TypedSpec> out) {
        if (n instanceof TypedNativeCall c
                && c.callee().qualifiedName()
                        .equals("meta::pure::functions::collection::concatenate")
                && c.args().size() == 2) {
            flattenConcat(c.args().get(0), out);
            flattenConcat(c.args().get(1), out);
            return;
        }
        out.add(n);
    }

    /** The predicate reads no variables beyond its own parameter and the
     * parameters of lambdas that lexically ENCLOSE the read — SHADOW-AWARE
     * (audit 21b F4): a nested lambda's param binds only within that
     * lambda's subtree. An outer variable that merely shares a param's
     * name stays FREE, so a correlated pred can never look closed by name
     * collision and get applied inside the target pipeline where the
     * outer row does not exist. (Conservative the other way stays fine:
     * over-refusing the lift is loud.) */
    private static boolean predClosedOverParam(TypedLambda pred) {
        Set<String> bound = new LinkedHashSet<>(pred.parameters());
        return pred.body().stream().allMatch(b -> readsOnly(b, bound));
    }

    private static boolean readsOnly(TypedSpec n, Set<String> allowed) {
        if (n instanceof TypedVariable v
                && !allowed.contains(v.name())) {
            return false;
        }
        if (n instanceof TypedLambda l) {
            Set<String> inner = new LinkedHashSet<>(allowed);
            inner.addAll(l.parameters());
            return l.body().stream().allMatch(b -> readsOnly(b, inner));
        }
        for (TypedSpec c : n.children()) {
            if (!readsOnly(c, allowed)) {
                return false;
            }
        }
        return true;
    }

    /** The filter's source is a navigation hop whose receiver chain bottoms
     * at a lambda variable — the shape the lift can rename. */
    /** MAP-WRAPPED filtered nav over a TO-ONE receiver
     * ({@code $p.firm->map(f|$f.address->filter(corr)).name}): map over
     * [0..1]/[1] IS direct application with empty propagation (pure), and
     * a navigation body propagates null — β-inline the mapper so the leaf
     * read lands on the filter and the leaf-read arm lifts the DIRECT
     * spelling (the exploding-sub machinery). Null when not this shape. */
    private @com.legend.Nullable TypedSpec liftMapWrappedFilterLeaf(
            TypedSpec n) {
        if (n instanceof TypedPropertyAccess paM
                && paM.source() instanceof TypedMap mw
                && mw.source().info().type() instanceof Type.ClassType
                && mw.source().info().multiplicity()
                        instanceof Multiplicity.Bounded mwb
                && Integer.valueOf(1).equals(mwb.upper())
                && mw.mapper().parameters().size() == 1
                && mw.mapper().body().size() == 1
                && mw.mapper().body().get(0) instanceof TypedFilter) {
            TypedSpec inlined = Substitution.inlineParam(
                    mw.mapper().body().get(0),
                    mw.mapper().parameters().get(0), mw.source());
            return liftFilteredHeads(new TypedPropertyAccess(
                    inlined, paM.property(), paM.info()), true);
        }
        return null;
    }

    /** CONCATENATED navigation streams read as a bare collection —
     * {@code $p.head->filter(f1).leaf} spelled over concatenate(...):
     * every branch is a (possibly filtered) navigation of the SAME head
     * property; the union lifts into ONE synthetic head #cN whose join
     * target is the UNION ALL of the branch pipelines (engine: one
     * unionalias subselect, LEFT-joined, row-exploding). Null = not
     * this shape. */
    private @com.legend.Nullable TypedSpec liftConcatArm(TypedSpec n) {
        if (n instanceof TypedPropertyAccess pa2
                && pa2.source() instanceof TypedNativeCall cc
                && cc.callee().qualifiedName()
                        .equals("meta::pure::functions::collection::concatenate")
                && cc.info().type() instanceof Type.ClassType
                && !(pa2.info().multiplicity()
                        instanceof Multiplicity.Bounded b2
                        && Integer.valueOf(1).equals(b2.upper()))) {
            return liftConcatStreams(cc, pa2);
        }
        return null;
    }

    private static boolean isLiftableNav(TypedSpec n) {
        if (n instanceof TypedPropertyAccess pa) {
            return navBottomsAtVar(pa.source());
        }
        if (n instanceof TypedMilestonedAccess ma) {
            return navBottomsAtVar(ma.source());
        }
        return false;
    }

    /** The filter node, looking through MULTIPLICITY wrappers — a
     * {@code ->toOne()} coercion, and {@code ->first()}/{@code ->head()}
     * (a qualifier body's own narrowing; batch 5): the engine compiles
     * first-over-filtered-nav as the PLAIN fanned join with the
     * predicate in the frame (conditionRightTableNested golden — no
     * LIMIT), so under charter decision 1 the wrappers are SQL-erased
     * here exactly like toOne; the read semantics are the LEFT join's
     * NULL-on-no-match either way. Wrappers STACK (toOne(first(...))). */
    private static TypedSpec filterBehindToOne(TypedSpec n) {
        while (n instanceof com.legend.compiler.spec.typed.TypedNativeCall c
                && c.args().size() == 1
                && (com.legend.builtin.Pure.isToOneCall(
                        c.callee().qualifiedName())
                    || c.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::first")
                    || c.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::head"))) {
            n = c.args().get(0);
        }
        return n;
    }

    /**
     * ONE-STEP canonicalization of a WRAPPED filtered-nav spelling to the
     * direct one the walk re-enters on — or {@code null} (no arm fires).
     * <ul>
     *   <li>exists over a FILTERED navigation folds the filter into the
     *       exists predicate — {@code exists(filter(X,p1),p2) ≡
     *       exists(X, p1 && p2)} (the qualifier-inlined spelling
     *       {@code $p.firm->toOne().emplByAge(30)->exists(pred)});</li>
     *   <li>WRAPPED value reads ({@code $f->map(f|$f.qual(...))
     *       ->filter(p2)->toOne().leaf}) canonicalize via
     *       {@link #canonNavChain} — the demand scan, the lift arms and
     *       the correlated-scalar arm then all see the one spelling they
     *       already handle; the TOP toOne wrapper is preserved (it marks
     *       the scalar first-row read).</li>
     * </ul>
     */
    private @com.legend.Nullable TypedSpec foldWrappedSpelling(TypedSpec n) {
        if (n instanceof TypedNativeCall ex
                && ex.callee().qualifiedName()
                        .equals("meta::pure::functions::collection::exists")
                && ex.args().size() == 2
                && filterBehindToOne(ex.args().get(0)) instanceof TypedFilter fx
                && fx.predicate().parameters().size() == 1
                && fx.predicate().body().size() == 1
                && fx.info().type() instanceof Type.ClassType
                && ex.args().get(1) instanceof TypedLambda exp
                && exp.parameters().size() == 1
                && exp.body().size() == 1) {
            return ex.withChildren(List.of(fx.source(), andMerge(fx.predicate(), exp)));
        }
        if (n instanceof TypedPropertyAccess paw
                && !(filterBehindToOne(paw.source())
                        instanceof TypedFilter fw
                        && isLiftableNav(fw.source()))) {
            TypedSpec un = filterBehindToOne(paw.source());
            TypedSpec canon = canonNavChain(un);
            if (canon != un && canon instanceof TypedFilter) {
                TypedSpec rewrapped = un == paw.source() ? canon
                        : new TypedNativeCall(
                                ((TypedNativeCall) paw.source()).callee(),
                                List.of(canon), paw.source().info());
                return new TypedPropertyAccess(rewrapped,
                        paw.property(), paw.info());
            }
        }
        return null;
    }

    /**
     * Canonicalize a WRAPPED filtered-navigation chain to the direct
     * spelling every downstream consumer (demand scan, this lift, the
     * correlated-scalar arm) already recognizes: β-reduce a map over ONE
     * instance ({@code $f->map(f|...)} — identity plumbing over a [1]
     * receiver), look through multiplicity-only {@code toOne} coercions,
     * and collapse stacked filters into ONE AND-merged predicate —
     * {@code filter(filter(nav,p1),p2) ≡ filter(nav, p1 && p2)}, and two
     * parks would mint two synthetic heads and cross-join the target.
     * Non-lift shapes return unchanged (identity — callers compare).
     */
    private TypedSpec canonNavChain(TypedSpec s) {
        TypedSpec u = filterBehindToOne(s);
        if (u != s) {
            return canonNavChain(u);
        }
        if (s instanceof TypedMap m && m.source() instanceof TypedVariable v
                && m.source().info().type() instanceof Type.ClassType
                && m.source().info().multiplicity()
                        instanceof Multiplicity.Bounded mb
                && Integer.valueOf(1).equals(mb.upper())
                && m.mapper().parameters().size() == 1
                && m.mapper().body().size() == 1) {
            String p = m.mapper().parameters().get(0);
            TypedSpec b = m.mapper().body().get(0);
            return canonNavChain(p.equals(v.name()) ? b
                    : Pipelines.rewriteRowReads(b, p, Map.of(), Set.of(),
                            vv -> new TypedVariable(v.name(), vv.info())));
        }
        if (s instanceof TypedFilter f
                && f.predicate().parameters().size() == 1
                && f.predicate().body().size() == 1) {
            TypedSpec src = canonNavChain(f.source());
            if (src instanceof TypedFilter inner
                    && inner.predicate().parameters().size() == 1
                    && inner.predicate().body().size() == 1) {
                return new TypedFilter(inner.source(),
                        andMerge(inner.predicate(), f.predicate()), f.info());
            }
            return src == f.source() ? s
                    : new TypedFilter(src, f.predicate(), f.info());
        }
        return s;
    }

    /** {@code λv. p1(v) && p2(v)} — alpha-aligned to p1's binder; the
     * merged predicate parks as ONE synthetic-head identity. */
    private TypedLambda andMerge(TypedLambda p1, TypedLambda p2) {
        var fns = ctx.findFunction("meta::pure::functions::boolean::and")
                .stream().filter(f -> f.parameters().size() == 2).toList();
        if (fns.size() != 1) {
            throw new IllegalStateException("resolver bug: expected exactly"
                    + " one 2-arg boolean::and, found " + fns.size());
        }
        String v = p1.parameters().get(0);
        TypedSpec b1 = p1.body().get(0);
        TypedSpec b2 = p2.parameters().get(0).equals(v) ? p2.body().get(0)
                : Pipelines.rewriteRowReads(p2.body().get(0),
                        p2.parameters().get(0), Map.of(), Set.of(),
                        vv -> new TypedVariable(v, vv.info()));
        return new TypedLambda(p1.parameters(),
                List.of(new TypedNativeCall(fns.get(0), List.of(b1, b2),
                        b1.info())), p1.info());
    }


    private static boolean navBottomsAtVar(TypedSpec n) {
        return switch (n) {
            case TypedVariable ignored -> true;
            case TypedPropertyAccess pa ->
                    navBottomsAtVar(pa.source());
            case TypedMilestonedAccess ma ->
                    navBottomsAtVar(ma.source());
            case TypedFilter f -> navBottomsAtVar(f.source());
            case TypedNativeCall c
                    when c.args().size() == 1 && com.legend.builtin.Pure.isToOneCall(c.callee().qualifiedName()) ->
                    navBottomsAtVar(c.args().get(0));
            default -> false;
        };
    }

    /** A synthetic head's underlying property name ({@code product#f0} /
     * {@code product#d1} → {@code product}); identity for ordinary heads.
     * DISPATCH-participating (Substitution's identity checks) — never
     * widen its vocabulary; message spelling belongs to
     * {@link #displayName}. */
    static String realHead(String head) {
        return JoinIdentity.of(head).prop();
    }

    /** MESSAGE-ONLY spelling of a head: strips the {@code #fN}/{@code #dN}
     * synthetics AND the subtype-dispatch {@code stc_..___} prefix (§2
     * hygiene — no internal identifier reaches a user-facing message).
     * Never consulted by dispatch. */
    static String displayName(String head) {
        if (com.legend.model.ClassMapping.isSubTypeColumn(head)) {
            return head.substring(head.indexOf("___") + 3);
        }
        return JoinIdentity.of(head).prop();
    }

    /** Apply the date splitter's verdicts in ONE identity-keyed pass
     * (rebuildChildren makes fresh nodes, so two sequential walks would
     * orphan the second map's identities): {@code strips} = CONTEXT-equal
     * dated accesses replaced by their PLAIN property equivalent (an
     * explicit date equal to the propagated context IS the propagation —
     * engine merge-by-identity — and must ride the ordinary propagation
     * channel, not the dated-fetch one); {@code renames} = foreign-dated
     * accesses renamed to date-fingerprinted synthetic heads. */
    TypedSpec replaceDatedNodes(TypedSpec n,
            IdentityHashMap<TypedSpec, String> renames,
            IdentityHashMap<TypedSpec, Boolean> strips) {
        if (strips.containsKey(n)) {
            var ma = (TypedMilestonedAccess) n;
            return new com.legend.compiler.spec.typed.TypedPropertyAccess(
                    replaceDatedNodes(ma.source(), renames, strips),
                    ma.property(), ma.info());
        }
        String newName = renames.get(n);
        if (newName != null) {
            var ma = (TypedMilestonedAccess) n;
            return new TypedMilestonedAccess(
                    replaceDatedNodes(ma.source(), renames, strips), newName,
                    ma.dates(), ma.sweep(), ma.info());
        }
        return rebuildChildren(n, c -> replaceDatedNodes(c, renames, strips));
    }

    /**
     * ONE-LEVEL generic rebuild: {@code f} applies to every child
     * expression (lambda bodies included; lambda/column structure is
     * preserved). Unknown node kinds pass through UNCHANGED — walkers
     * built on this are best-effort by design (an unvisited shape keeps
     * its loud downstream error, never silent SQL).
     */
    static TypedSpec rebuildChildren(TypedSpec n,
            UnaryOperator<TypedSpec> f) {
        return switch (n) {
            case TypedProject p ->
                    new TypedProject(
                            f.apply(p.source()),
                            p.columns().stream().map(c ->
                                    new TypedFuncCol(
                                            c.name(), (TypedLambda) f.apply(c.fn())))
                                    .toList(),
                            p.info());
            case TypedFilter fl -> new TypedFilter(f.apply(fl.source()),
                    (TypedLambda) f.apply(fl.predicate()), fl.info());
            case TypedGroupBy gb -> new TypedGroupBy(f.apply(gb.source()),
                    gb.keys().stream().map(k -> new TypedGroupBy.GroupKey(
                            k.column(), k.fn().map(fn -> (TypedLambda) f.apply(fn))))
                            .toList(),
                    gb.aggs().stream().map(a -> new TypedAggCol(a.name(),
                            (TypedLambda) f.apply(a.map()), a.reduce(),
                            a.orderKey() == null ? null
                                    : (TypedLambda) f.apply(a.orderKey()),
                            a.orderAsc()))
                            .toList(),
                    gb.info());
            case TypedSortBy sb -> new TypedSortBy(f.apply(sb.source()),
                    (TypedLambda) f.apply(sb.key()), sb.ascending(),
                    sb.keyAlias(), sb.info());
            case com.legend.compiler.spec.typed.TypedSort so ->
                    new com.legend.compiler.spec.typed.TypedSort(
                            f.apply(so.source()), so.keys(),
                            so.pureNullOrder(), so.info());
            case TypedLimit l -> new TypedLimit(f.apply(l.source()),
                    l.count(), l.info());
            case TypedDrop d -> new TypedDrop(f.apply(d.source()),
                    d.count(), d.info());
            case TypedSlice sl -> new TypedSlice(f.apply(sl.source()),
                    sl.start(), sl.stop(), sl.info());
            case TypedFrom fr -> new TypedFrom(f.apply(fr.source()),
                    fr.mapping(), fr.runtime(), fr.chainMappings(),
                    fr.jsonSources(), fr.sqlSetups(), fr.csvSetups(), fr.connectionName(),
                    fr.info());
            case TypedLambda l -> new TypedLambda(l.parameters(),
                    l.body().stream().map(f).toList(), l.info());
            case TypedNativeCall c ->
                    c.withChildren(c.args().stream().map(f).toList());
            case TypedPropertyAccess pa ->
                    new TypedPropertyAccess(
                            f.apply(pa.source()), pa.property(), pa.info());
            case TypedMilestonedAccess ma ->
                    new TypedMilestonedAccess(
                            f.apply(ma.source()), ma.property(),
                            ma.dates(), ma.sweep(), ma.info());
            case TypedMap m ->
                    new TypedMap(
                            f.apply(m.source()),
                            (TypedLambda) f.apply(m.mapper()), m.info());
            case TypedIf i ->
                    new TypedIf(
                            f.apply(i.condition()), f.apply(i.thenBranch()),
                            i.elseBranch().map(f), i.info());
            case TypedCollection c ->
                    new TypedCollection(
                            c.elements().stream().map(f).toList(), c.info());
            case TypedCast c ->
                    new TypedCast(
                            f.apply(c.source()), c.target(), c.info(),
                            c.wire());
            default -> n;
        };
    }

    /** Lifted filtered-navigation heads: synthetic name → the user
     * predicate parked on the head ({@link #liftFilteredHeads}).
     * Append-only across nested resolutions — names are counter-unique. */
    /** CORRELATED lifted predicates (read the OUTER lambda's row too):
     * applied at the association JOIN CONDITION, where both rows are in
     * scope — never at the target pipeline (audit 14 B-F1's correlation
     * pass, finally built). */
    private final Map<String, TypedLambda> corrPreds =
            new java.util.LinkedHashMap<>();

    /** POSITIONAL heads ({@code #pN}) → the picked index k. */
    private final Map<String, Integer> positional = new LinkedHashMap<>();
    private final Map<String, TypedLambda> preds =
            new LinkedHashMap<>();

    /** {@code #cN} heads: synthetic name → the ORDERED branch predicates
     * (null members = unfiltered branches). */
    private final Map<String, List<TypedLambda>> branchPreds =
            new LinkedHashMap<>();

    /** (prop, branch predicates) → minted {@code #cN} name: the same
     * stream expression appearing twice shares ONE join identity. */
    private final Map<List<Object>, String> concatMemo =
            new LinkedHashMap<>();

    /** Alpha-normalized predicate for identity comparison: separate
     * β-inlines of the same derived property differ only in the fresh
     * parameter name — rename to a fixed one so record equality sees
     * through it. */
    private static TypedLambda canonicalPred(TypedLambda pred) {
        // FULL alpha-normalization (audit 16): the top-level rename alone
        // let nested-lambda fresh names (_iN from separate β-inlines of one
        // derived property) defeat the memo — two identities, two joins,
        // row multiplication. Canonical names contain '#', unspellable as
        // pure variables, so user code can never capture them.
        return (TypedLambda) alphaNormalize(pred,
                new LinkedHashMap<>(), new int[]{0});
    }

    private static TypedSpec alphaNormalize(@com.legend.Nullable TypedSpec n,
            Map<String, String> env, int[] counter) {
        if (n instanceof TypedVariable v) {
            String canonical = env.get(v.name());
            return canonical == null ? v
                    : new TypedVariable(canonical, v.info());
        }
        if (n instanceof TypedLambda l) {
            Map<String, String> inner = new LinkedHashMap<>(env);
            List<String> ps = new java.util.ArrayList<>(l.parameters().size());
            for (String p : l.parameters()) {
                String c = "#a" + counter[0]++;
                inner.put(p, c);
                ps.add(c);
            }
            return new TypedLambda(ps,
                    l.body().stream()
                            .map(b -> alphaNormalize(b, inner, counter))
                            .toList(),
                    l.info());
        }
        return rebuildChildren(java.util.Objects.requireNonNull(n, "n"),
                c -> alphaNormalize(c, env, counter));
    }

    /** The variable a liftable navigation chain bottoms at. */
    private static String bottomVarOf(@com.legend.Nullable TypedSpec n) {
        return switch (n) {
            case TypedVariable v -> v.name();
            case TypedPropertyAccess pa -> bottomVarOf(pa.source());
            case TypedMilestonedAccess ma -> bottomVarOf(ma.source());
            case TypedFilter f -> bottomVarOf(f.source());
            case TypedNativeCall c when c.args().size() == 1 ->
                    bottomVarOf(c.args().get(0));
            case null, default -> throw new IllegalStateException(
                    "resolver bug: liftable nav does not bottom at a variable");
        };
    }

    private int count = 0;

    /** Column lambdas born from VALUES-position map terminals: pure
     * flattening drops empties there, so the TDS lift (whose LEFT-join
     * NULL row is the point) must NOT fire inside them.
     * IDENTITY-keyed (audit 23 residual, documented): the gate holds only
     * while no pass REBUILDS the column lambda between registration and
     * the lift — a rebuilt (structurally-equal, identity-different)
     * lambda would silently take the TDS lift and emit a NULL row where
     * pure flattening drops it. Registration and consumption sit in THIS
     * class within one liftFilteredHeads walk; keep it that way. */
    private final Set<TypedLambda> valuesLambdas =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** A lifted head's (and a drilled synthetic MID component's) predicate
     * reads are TAILS too: they pull the target's own slots exactly like
     * demanded leaves. */
    List<List<String>> predTailsFor(List<String> path, int mid) {
        List<List<String>> predTails = new java.util.ArrayList<>();
        Set<String> predComponents = new java.util.LinkedHashSet<>();
        predComponents.add(path.get(0));
        if (mid > 1) {
            predComponents.add(path.get(mid - 1));
        }
        for (String pcpt : predComponents) {
            for (TypedLambda liftedPred : allPreds(pcpt)) {
                Set<List<String>> predPaths = new java.util.LinkedHashSet<>();
                for (TypedSpec b : liftedPred.body()) {
                    FlattenOps.consumedPaths(b, liftedPred.parameters().get(0),
                            predPaths);
                }
                predTails.addAll(predPaths);
            }
        }
        return predTails;
    }


    /** #69 (audit-22 follow-on): a CORRELATED pred's OUTER-variable
     * reads are PARENT demand — the lift moved the only occurrence of
     * {@code $f.<head>...} out of the projection column, so the ordinary
     * scans no longer see it and the head's navigate material never
     * registered (the Substitution 'class-typed slot' wall family). */
    void corrPredOuterDemand(TypedLambda fn, Set<List<String>> out) {
        if (fn.parameters().isEmpty()) {
            return;
        }
        String uv = fn.parameters().get(0);
        for (TypedLambda corr : allCorrelatedPreds()) {
            for (TypedSpec b : corr.body()) {
                FlattenOps.consumedPaths(b, uv, out);
            }
        }
    }


}
