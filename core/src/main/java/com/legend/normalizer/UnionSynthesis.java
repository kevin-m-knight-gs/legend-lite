// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.compiler.SynthFqn;
import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;
import com.legend.error.NotImplementedException;
import com.legend.model.Multiplicity;
import com.legend.model.NormalizedModel;
import com.legend.model.ParsedModel;
import com.legend.model.TypeExpression;
import com.legend.model.AssociationDefinition;
import com.legend.model.AssociationMapping;
import com.legend.model.AssociationPropertyMapping;
import com.legend.model.ClassDefinition;
import com.legend.model.ClassMapping;
import com.legend.model.ComparisonOp;
import com.legend.model.DatabaseDefinition;
import com.legend.model.EnumerationMapping;
import com.legend.model.FilterMapping;
import com.legend.model.FilterPointer;
import com.legend.model.FunctionDefinition;
import com.legend.model.JoinChainElement;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.LogicalOp;
import com.legend.model.MappingDefinition;
import com.legend.model.MappingInclude;
import com.legend.model.PackageableElement;
import com.legend.model.PropertyMapping;
import com.legend.model.Realization;
import com.legend.model.RelationalDataType;
import com.legend.model.RelationalOperation;
import com.legend.model.SynthHat;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.CBoolean;
import com.legend.model.spec.CDate;
import com.legend.model.spec.CDecimal;
import com.legend.model.spec.CFloat;
import com.legend.model.spec.CInteger;
import com.legend.model.spec.CString;
import com.legend.model.spec.ColSpec;
import com.legend.model.spec.ColSpecArray;
import com.legend.model.spec.EnumValue;
import com.legend.model.spec.KeyExpression;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.NewInstance;
import com.legend.model.spec.NewInstanceCast;
import com.legend.model.spec.PackageableElementPtr;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.TypeAnnotation;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
/**
 * Union/inheritance operation-mapping synthesis: member-set concatenation, route classification, nav lifts and inbound route keys. Split from MappingNormalizer (the Doors split).
 */
final class UnionSynthesis {

    private UnionSynthesis() {}

    /**
     * The Union operation mapping for {@code classFqn} in {@code md} or its
     * includes (own wins), or {@code null}. Member ORDINAL order = the
     * union's declaration order = the synthesized concatenate's thread
     * order = the engine's {@code _N} key suffix.
     */
    static ClassMapping.@com.legend.Nullable Union unionForClass(LegacyMappingDefinition md,
            ModelBuilder model, @com.legend.Nullable String classFqn) {
        for (ClassMapping cm : md.classMappings()) {
            if (cm instanceof ClassMapping.Union u
                    && u.className().equals(classFqn)) {
                return u;
            }
        }
        for (MappingInclude inc : md.includes()) {
            LegacyMappingDefinition inner =
                    model.findLegacyMapping(inc.mappingPath()).orElse(null);
            if (inner != null) {
                ClassMapping.Union u = unionForClass(inner, model, classFqn);
                if (u != null) {
                    return u;
                }
            }
        }
        return null;
    }

    /** One routed navigation entry: the target's union-member ORDINAL
     * (declaration order = concatenate thread order = the engine's
     * {@code _N} suffix) and the entry's own join. Ordinal {@code -1}
     * marks a root/sole-set route (the un-routed navigation). */
    record UnionRoute(int targetOrdinal, PropertyMapping.Join join) {
    }

    /** Extends-merge identity: (property name, route) — per-set duplicates
     * of a routed property are distinct mappings. */
    static String pmIdentity(PropertyMapping pm) {
        return pm.propertyName() + ' '
                + (pm instanceof PropertyMapping.Join j
                        && j.targetSetId() != null ? j.targetSetId() : "");
    }

    /**
     * The ordinal of the member whose set id OR extends-LINEAGE matches
     * {@code setId}: a re-rooted union ({@code Person[mySet1] extends
     * [set1]}) is navigated by routes naming the ANCESTOR sets — the
     * corpus extends-of-union shape. {@code -1} when no member matches.
     */
    /**
     * Whether the property's routes need NO target-side discrimination:
     * they cover EVERY member of the target union. The engine then joins
     * on the MERGED (unsuffixed) target key — both threads project it, so
     * every member matches (snapshot-union AND partially-milestoning
     * goldens: prodFk_0 = id OR prodFk_1 = id, target side shared even
     * with different joins per member). Partial coverage keeps the
     * member-suffixed NULL-crossed form (un-routed members must not
     * match — the TDSNull golden).
     */
    static boolean mergedTargetRoutes(List<UnionRoute> routes, ClassMapping.@com.legend.Nullable Union targetUnion) {
        if (targetUnion == null) {
            return false;
        }
        // Merge (single un-suffixed condition) ONLY when every route names
        // the SAME join — then the one condition IS every entry's condition
        // (audit 12: coverage alone dropped the second route's join
        // entirely; J1(FK1=ID)+J2(FK2=ID) matched member 2 by the wrong
        // key). Distinct joins keep the suffixed OR.
        Set<Integer> ords = new HashSet<>();
        Set<String> joins = new HashSet<>();
        for (UnionRoute r : routes) {
            if (r.targetOrdinal() < 0 || r.join().joins().size() != 1) {
                return false;
            }
            ords.add(r.targetOrdinal());
            JoinChainElement hop = r.join().joins().get(0);
            joins.add((hop.databaseName() != null ? hop.databaseName()
                    : r.join().database()) + "@" + hop.joinName());
        }
        return ords.size() == targetUnion.memberSetIds().size()
                && joins.size() == 1;
    }

    static int memberOrdinalOf(List<String> memberIds,
            LegacyMappingDefinition md, ModelBuilder model, String setId) {
        int direct = memberIds.indexOf(setId);
        if (direct >= 0) {
            return direct;
        }
        for (int i = 0; i < memberIds.size(); i++) {
            ClassMapping m = MappingNormalizer.findSetById(md, model, memberIds.get(i));
            Set<String> seen = new HashSet<>();
            while (m instanceof ClassMapping.Relational r
                    && r.extendsSetId() != null && seen.add(r.extendsSetId())) {
                if (r.extendsSetId().equals(setId)) {
                    return i;
                }
                m = MappingNormalizer.findSetById(md, model, r.extendsSetId());
            }
        }
        return -1;
    }

    /**
     * Classify every {@code prop[setId]}-routed class-typed Join PM of
     * {@code rcm} from its OWN {@code targetSetId} (per-PM fidelity —
     * audit 11: the name-keyed map's put() lost duplicates and made the
     * outcome depend on textual PM order). Outcomes per property:
     * <ul>
     *   <li>every route hits a member of the target class's union &rarr;
     *       {@code p.unionRoutes} (ONE navigate, OR over the entries,
     *       each member-suffixed — engine parity; coverage of ALL members
     *       is NOT assumed, un-routed members read NULL keys);</li>
     *   <li>every route hits the target's root/sole set &rarr; the
     *       un-routed navigation (duplicates dedup at emission);</li>
     *   <li>anything else (unknown set, non-root non-member set, chained
     *       join on a routed entry, mixed root+member) &rarr; the property
     *       DROPS from this synthesis with the reason on the poison
     *       ledger; demanding it fails loudly.</li>
     * </ul>
     */
    static void classifyUnionRoutes(LegacyMappingDefinition md,
            ClassMapping.Relational rcm, ModelBuilder model, Pipeline p) {
        Map<String, List<PropertyMapping.Join>> routedByProp = new LinkedHashMap<>();
        for (PropertyMapping pm : rcm.propertyMappings()) {
            if (pm instanceof PropertyMapping.Join j && j.targetSetId() != null) {
                routedByProp.computeIfAbsent(j.propertyName(),
                        k -> new ArrayList<>()).add(j);
            }
        }
        for (var e : routedByProp.entrySet()) {
            String prop = e.getKey();
            ClassDefinition owner = model.findClass(rcm.className()).orElse(null);
            TypeExpression pt = owner == null ? null
                    : MappingNormalizer.findPropertyTypeDeep(owner, prop, model);
            String targetClass = pt instanceof TypeExpression.NameRef nr
                    && model.findClass(nr.name()).isPresent() ? nr.name() : null;
            ClassMapping.Union tu = targetClass == null ? null
                    : unionForClass(md, model, targetClass);
            // FIX-A (audit-17 bucket analysis): an INHERITANCE op is a
            // union at the routing level — member set ids in the shared
            // enumeration's order
            List<String> memberIds = tu != null ? tu.memberSetIds() : null;
            if (memberIds == null && targetClass != null) {
                ClassMapping.Inheritance tih =
                        inheritanceForClass(md, model, targetClass);
                if (tih != null) {
                    memberIds = inheritanceMembers(md, tih, model).stream()
                            .map(MappingNormalizer::setIdOf).toList();
                }
            }
            List<UnionRoute> routes = new ArrayList<>();
            String poison = null;
            for (PropertyMapping.Join j : e.getValue()) {
                ClassMapping set = MappingNormalizer.findSetById(md, model, j.targetSetId());
                if (set == null) {
                    poison = "unknown mapping set '" + j.targetSetId() + "'";
                    break;
                }
                int ord = memberIds == null ? -1
                        : memberOrdinalOf(memberIds, md, model,
                                j.targetSetId());
                // engine rootClassMappingByClass: the * set, or the class's
                // SOLE set (sole-ness judged in the OWNING mapping's scope)
                boolean rootOrSole = set instanceof ClassMapping.Relational tr
                        && (tr.root() || md.classMappings().stream()
                                .filter(x -> x.className().equals(tr.className()))
                                .count() == 1);
                if (ord >= 0) {
                    routes.add(new UnionRoute(ord, j));
                } else if (memberIds != null) {
                    // the TARGET class is union-mapped and this route's set
                    // is not among the members: the set is unreachable from
                    // the union extent — engine consults only member
                    // routes, so the entry is DEAD, never a root route and
                    // never a poison (multipleChainedJoins V4: included
                    // y2/y3 sets beside a (y0, y1) union; root/sole-ness
                    // judged in the requesting mapping's scope would
                    // misread them as roots)
                    continue;
                } else if (rootOrSole) {
                    routes.add(new UnionRoute(-1, j));
                } else if (e.getValue().size() == 1) {
                    // a SINGLE set-pinned route to a NON-root set: the
                    // navigate emits un-routed and DISPATCHES through the
                    // recorded routed-set hint at resolve (getForNav ->
                    // set-pinned ClassSources.get — the target set's own
                    // ~filter pipeline rides). employees2[p2] over
                    // multi-set Person; no union machinery involved.
                    routes.add(new UnionRoute(-1, j));
                } else {
                    poison = "NON-root mapping set '" + j.targetSetId()
                            + "' — MULTI-route dispatch outside union members"
                            + " is a roadmap feature";
                    break;
                }
            }
            if (poison == null && routes.stream()
                    .anyMatch(r -> r.targetOrdinal() >= 0)
                    && routes.stream().anyMatch(r -> r.targetOrdinal() < 0)) {
                poison = "MIXED root-set and union-member routes";
            }
            // CHAINED member routes come in two engine shapes, both
            // accepted here: SHARED-PREFIX (unionOfViews golden — the
            // identical prefix hops emit ONCE as physical joins, the final
            // hop dispatches per member) and PER-ARM (V4 pair routes /
            // unionOfViews2 — routes diverge, each route's mid hops
            // materialize INSIDE the owning member's thread and the ONE
            // navigate reads each route's FIRST hop). The split is decided
            // by uniformChainedRoutes at the emitter AND the inbound key
            // collector — the same predicate, never allowed to drift.
            if (poison != null) {
                p.droppedRoutedProps.add(prop);
                model.mappingPoisons.merge(
                        md.qualifiedName() + "::" + rcm.className(),
                        "property '" + prop + "' routes to " + poison
                                + "; the property is dropped from this synthesis",
                        (a, b) -> a + "; " + b);
                continue;
            }
            if (routes.stream().allMatch(r -> r.targetOrdinal() < 0)) {
                continue;   // root routes = the un-routed navigation
            }
            p.unionRoutes.put(prop, routes);
        }
    }

    /**
     * An Operation UNION class mapping: the extent is UNION ALL of the
     * member sets. Each member synthesizes its own pipeline+fields; the
     * SHARED SCALAR properties (declared type not a model class) project to
     * property-named columns, the projections concatenate, and one map
     * terminal reads the aligned row. Properties outside the shared set are
     * absent from the binding table — demanding one is loud downstream.
     */
    static ValueSpecification synthUnion(LegacyMappingDefinition md,
                                                ClassMapping.Union u,
                                                ModelBuilder model) {
        if (u.memberSetIds().isEmpty()) {
            throw new NotImplementedException(
                    "Operation union with no member sets; class="
                  + u.className() + ", mapping=" + md.qualifiedName());
        }
        // member sets resolve by setId ACROSS INCLUDES; a member may map a
        // SUBCLASS of the operation class (special_union over an
        // inheritance hierarchy) — the shared-property projection over the
        // operation class is the semantics either way
        Map<String, ClassMapping> bySetId = new LinkedHashMap<>();
        MappingNormalizer.collectIncludedSetIds(md, model, bySetId, new HashSet<>());
        for (ClassMapping cm : md.classMappings()) {
            bySetId.put(MappingNormalizer.setIdOf(cm), cm);
        }
        List<ClassMapping> memberSets = new ArrayList<>();
        for (String setId : u.memberSetIds()) {
            ClassMapping member = bySetId.get(setId);
            if (member instanceof ClassMapping.Pure) {
                // MIXED-KIND union (route b, docs/XSTORE_LEG.md): a Pure
                // member's relation exists only at resolve time — record
                // the member list for ClassSources' resolver-side arm
                // synthesis and withhold the eager class function (the
                // throw lands on the poison ledger; the resolver route
                // recognizes the registry before the ledger surfaces).
                model.mixedUnions.put(
                        md.qualifiedName() + "::" + u.className(),
                        List.copyOf(u.memberSetIds()));
                throw new NotImplementedException(
                        "Operation union member set '" + setId + "' of class '"
                      + u.className() + "' is a Pure (M2M) set — the mixed-kind"
                      + " union extent synthesizes at the resolver; mapping="
                      + md.qualifiedName());
            }
            if (!(member instanceof ClassMapping.Relational)
                    && !(member instanceof ClassMapping.RelationFunction)) {
                throw new NotImplementedException(
                        "Operation union member set '" + setId + "' of class '"
                      + u.className() + "' is " + (member == null ? "missing"
                              : "not a Relational or Relation(~func) set")
                      + "; mapping=" + md.qualifiedName());
            }
            // a member must map the operation class or a SUBCLASS — a
            // stray setId landing on an unrelated class with coincidental
            // property names would union unrelated rows (audit 8 S8)
            if (!isSubclassOf(member.className(), u.className(), model)) {
                throw new ModelException(
                        LegendCompileException.Phase.NORMALIZE,
                        "Operation union member set '" + setId + "' maps '"
                      + member.className() + "', which is not '" + u.className()
                      + "' or a subclass; mapping=" + md.qualifiedName());
            }
            memberSets.add(member);
        }
        // per-pair AssociationMapping entries ([sourceSet, targetSet]) land
        // on their owning MEMBER set as routed class-typed Join PMs — the
        // engine dispatches union navigation per member pair
        Map<String, List<PropertyMapping.Join>> pairEntries = new LinkedHashMap<>();
        AssociationSynthesis.collectPairAssociationEntries(md, model, u.className(), pairEntries,
                new HashSet<>());
        if (!pairEntries.isEmpty()) {
            for (int i = 0; i < memberSets.size(); i++) {
                if (!(memberSets.get(i) instanceof ClassMapping.Relational mr)) {
                    continue;
                }
                // OWN entries + EXTENDS-routed entries merged (e[aSet1,
                // eSet1] serves bSet1 extends aSet1 ALONGSIDE bSet1's own
                // h entry) — engine golden testExtendsForPropertyMapping
                // WithUnion result2 joins ONLY the routed thread (the
                // other thread carries a NULL key). Own entries first;
                // the pmIdentity dedup below keeps them authoritative.
                List<PropertyMapping.Join> add = new ArrayList<>();
                String cur = MappingNormalizer.setIdOf(mr);
                Set<String> seenSets = new HashSet<>();
                while (cur != null && seenSets.add(cur)) {
                    List<PropertyMapping.Join> lvl = pairEntries.get(cur);
                    if (lvl != null) {
                        add.addAll(lvl);
                    }
                    ClassMapping up =
                            MappingNormalizer.findSetById(md, model, cur);
                    cur = up instanceof ClassMapping.Relational ur
                            ? ur.extendsSetId() : null;
                }
                if (add.isEmpty()) {
                    continue;
                }
                List<PropertyMapping> pms = new ArrayList<>(mr.propertyMappings());
                for (PropertyMapping.Join j : add) {
                    if (pms.stream().noneMatch(p ->
                            pmIdentity(p).equals(pmIdentity(j)))) {
                        pms.add(j);
                    }
                }
                memberSets.set(i, new ClassMapping.Relational(mr.className(),
                        mr.setId(), mr.extendsSetId(), mr.root(), mr.mainTable(),
                        mr.filter(), mr.distinct(), mr.groupBy(), mr.primaryKey(),
                        pms, mr.sourceUrl(), mr.propertyTargetSets()));
            }
        }
        return synthMemberUnion(md, u.className(), memberSets, model);
    }

    /**
     * Inheritance Operation: the extent is the UNION of every Relational
     * set mapped for the class's SUBCLASSES (transitively; nested
     * union/inheritance operations expand to their concrete members).
     * Queries on the base class can only touch base-class-typed properties,
     * so the shared-property projection over the base owner is exactly the
     * engine's router semantics.
     */
    static ValueSpecification synthInheritance(LegacyMappingDefinition md,
            ClassMapping.Inheritance ih, ModelBuilder model) {
        // ENGINE ALGORITHM (router_operations.pure getMappedLeafTypes) —
        // the ordered member enumeration is SHARED with route
        // classification (inheritanceMembers): ordinal alignment by
        // construction.
        List<ClassMapping.Relational> members =
                inheritanceMembers(md, ih, model);
        if (members.isEmpty()) {
            throw new NotImplementedException(
                    "inheritance Operation for '" + ih.className()
                    + "' finds no mapped subclass sets; mapping="
                    + md.qualifiedName());
        }
        if (members.size() == 1) {
            return MappingNormalizer.synthRelational(md, members.get(0), model);
        }
        return synthMemberUnion(md, ih.className(), members, model);
    }

    /**
     * The ORDERED member Relational sets of an inheritance op — ONE
     * enumeration shared by {@link #synthInheritance} (concatenate thread
     * order) and route classification (ordinal computation), so the
     * ordinals align BY CONSTRUCTION (misalignment = silently wrong rows).
     */
    static List<ClassMapping.Relational> inheritanceMembers(
            LegacyMappingDefinition md, ClassMapping.Inheritance ih,
            ModelBuilder model) {
        LinkedHashSet<ClassMapping> chosen = new LinkedHashSet<>();
        collectInheritanceMembers(md, ih.className(), model, chosen);
        List<ClassMapping.Relational> members = new ArrayList<>();
        for (ClassMapping cm : chosen) {
            switch (cm) {
                case ClassMapping.Relational mr -> members.add(mr);
                case ClassMapping.Union u2 -> {
                    Map<String, ClassMapping> bySetId = new LinkedHashMap<>();
                    MappingNormalizer.collectIncludedSetIds(md, model, bySetId, new HashSet<>());
                    for (ClassMapping own : md.classMappings()) {
                        bySetId.put(MappingNormalizer.setIdOf(own), own);
                    }
                    for (String setId : u2.memberSetIds()) {
                        if (bySetId.get(setId) instanceof ClassMapping.Relational mr2) {
                            members.add(mr2);
                        } else {
                            throw new NotImplementedException(
                                    "inheritance member union set '" + setId
                                    + "' is not a Relational set; mapping="
                                    + md.qualifiedName());
                        }
                    }
                }
                default -> throw new NotImplementedException(
                        "inheritance Operation member for '" + cm.className()
                        + "' is a " + cm.getClass().getSimpleName()
                        + " mapping — not supported yet; mapping="
                        + md.qualifiedName());
            }
        }
        return members;
    }

    /** The inheritance op mapping a class, own then includes — the
     * Inheritance sibling of {@link #unionForClass}. */
    static ClassMapping.@com.legend.Nullable Inheritance inheritanceForClass(LegacyMappingDefinition md,
            ModelBuilder model, String classFqn) {
        for (ClassMapping cm : md.classMappings()) {
            if (cm instanceof ClassMapping.Inheritance ih
                    && ih.className().equals(classFqn)) {
                return ih;
            }
        }
        for (MappingInclude inc : md.includes()) {
            LegacyMappingDefinition inner =
                    model.findLegacyMapping(inc.mappingPath()).orElse(null);
            if (inner != null) {
                ClassMapping.Inheritance ih = inheritanceForClass(inner, model, classFqn);
                if (ih != null) {
                    return ih;
                }
            }
        }
        return null;
    }

    /** The engine's leaf-most-root member selection for an inheritance op. */
    static void collectInheritanceMembers(LegacyMappingDefinition md,
            String base, ModelBuilder model, Set<ClassMapping> chosen) {
        // ROOT class mapping per class, includes first (own definitions win)
        Map<String, ClassMapping> rootByClass = new LinkedHashMap<>();
        collectRootClassMappings(md, model, rootByClass, new HashSet<>());
        // strict specializations of base, and their leaves
        List<String> subs = new ArrayList<>();
        model.classes().forEach(cd -> {
            String fqn = cd.qualifiedName();
            if (!fqn.equals(base) && isSubclassOf(fqn, base, model)) {
                subs.add(fqn);
            }
        });
        List<String> leaves = subs.stream()
                .filter(c -> subs.stream().noneMatch(o -> !o.equals(c)
                        && isSubclassOf(o, c, model)))
                .toList();
        for (String leaf : leaves) {
            // nearest mapped ancestor at or above the leaf, STRICTLY below base
            ArrayDeque<String> level = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            level.add(leaf);
            outer:
            while (!level.isEmpty()) {
                int n = level.size();
                for (int i = 0; i < n; i++) {
                    String c = level.poll();
                    if (!seen.add(c) || c.equals(base)) {
                        continue;
                    }
                    ClassMapping cm = rootByClass.get(c);
                    if (cm != null) {
                        if (cm instanceof ClassMapping.Inheritance) {
                            collectInheritanceMembers(md, c, model, chosen);
                        } else {
                            chosen.add(cm);
                        }
                        break outer;
                    }
                    ClassDefinition cd = model.findClass(c).orElse(null);
                    if (cd != null) {
                        for (TypeExpression sup : cd.superClasses()) {
                            if (sup instanceof TypeExpression.NameRef nr) {
                                level.add(nr.name());
                            }
                        }
                    }
                }
            }
        }
    }

    /** ROOT set per class across this mapping + its includes (own wins). */
    static void collectRootClassMappings(LegacyMappingDefinition md,
            ModelBuilder model, Map<String, ClassMapping> out, Set<String> seen) {
        for (MappingInclude inc : md.includes()) {
            if (seen.add(inc.mappingPath())) {
                LegacyMappingDefinition included =
                        model.findLegacyMapping(inc.mappingPath()).orElse(null);
                if (included != null) {
                    collectRootClassMappings(included, model, out, seen);
                }
            }
        }
        Map<String, Integer> setsPerClass = new LinkedHashMap<>();
        for (ClassMapping cm : md.classMappings()) {
            setsPerClass.merge(cm.className(), 1, Integer::sum);
        }
        for (ClassMapping cm : md.classMappings()) {
            // engine rootClassMappingByClass: the * set, or the class's
            // SOLE set (corpus mappings often omit * on singletons)
            if (cm.root() || java.util.Objects.requireNonNull(setsPerClass.get(cm.className())) == 1) {
                out.put(cm.className(), cm);
            }
        }
    }

    /** {@code candidate} equals {@code base} or transitively extends it. */
    static boolean isSubclassOf(String candidate, String base, ModelBuilder model) {
        if (candidate.equals(base)) {
            return true;
        }
        ClassDefinition cd = model.findClass(candidate).orElse(null);
        if (cd == null) {
            return false;
        }
        for (TypeExpression sup : cd.superClasses()) {
            if (sup instanceof TypeExpression.NameRef nr
                    && isSubclassOf(nr.name(), base, model)) {
                return true;
            }
        }
        return false;
    }

    /**
     * SUBTYPE COLUMNS (engine router subType dispatch): each member whose
     * class is a proper SUBCLASS of the union root carries every scalar
     * property it maps under a class-qualified synthetic column
     * ({@link ClassMapping#subTypeColumn}) — its own thread reads the
     * mapped value, every other thread a typed NULL — so
     * {@code ->subType(@Sub).prop} reads NULL off non-member rows by
     * construction. Forced casts of SHARED properties included: the
     * subtype column is thread-local, never the aligned column.
     */
    private static Map<String, LinkedHashSet<String>> subTypeDispatchProps(
            String className, List<ClassMapping> members,
            List<MappingNormalizer.RelationalParts> parts, ModelBuilder model) {
        Map<String, LinkedHashSet<String>> subTypeProps = new LinkedHashMap<>();
        for (int j = 0; j < members.size(); j++) {
            String memberClass = members.get(j).className();
            if (memberClass.equals(className)) {
                continue;
            }
            ClassDefinition mcd = model.findClass(memberClass).orElse(null);
            // cast TARGETS: the member class and every ancestor strictly
            // below the union root — a cast to an INTERMEDIATE class
            // (subType(@RoadVehicle) over a Car|Bicycle union) is owned by
            // every conforming member thread
            for (String target : selfAndAncestorsBelow(memberClass, className,
                    model)) {
                ClassDefinition tcd = model.findClass(target).orElse(null);
                for (String prop : parts.get(j).fields().keySet()) {
                    TypeExpression t = mcd == null ? null
                            : MappingNormalizer.findPropertyTypeDeep(mcd, prop, model);
                    boolean scalar = t instanceof TypeExpression.NameRef nr
                            && model.findClass(nr.name()).isEmpty();
                    boolean visibleOnTarget = tcd != null
                            && MappingNormalizer.findPropertyTypeDeep(tcd, prop,
                                    model) != null;
                    if (scalar && visibleOnTarget) {
                        subTypeProps.computeIfAbsent(target,
                                k -> new LinkedHashSet<>()).add(prop);
                    }
                }
            }
        }
        // MEMBERSHIP WITNESS: a cast target some member does NOT conform
        // to needs row RESTRICTION at to-many navigation positions — emit
        // a witness column (TRUE in conforming threads, NULL elsewhere).
        // Total-membership targets get NO witness: the cast is row-neutral.
        for (var en : subTypeProps.entrySet()) {
            for (ClassMapping m : members) {
                if (!m.className().equals(className)
                        && !isSubclassOf(m.className(), en.getKey(), model)) {
                    en.getValue().add(MEMBER_WITNESS);
                    break;
                }
            }
        }
        return subTypeProps;
    }

    static final String MEMBER_WITNESS = ClassMapping.memberWitness();

    /** {@code cls} plus its transitive superclasses, excluding {@code root}
     * and anything above it. */
    private static LinkedHashSet<String> selfAndAncestorsBelow(String cls,
            String root, ModelBuilder model) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>();
        work.add(cls);
        while (!work.isEmpty()) {
            String cur = work.poll();
            if (cur.equals(root) || !out.add(cur)) {
                continue;
            }
            ClassDefinition cd = model.findClass(cur).orElse(null);
            if (cd == null) {
                continue;
            }
            for (TypeExpression sup : cd.superClasses()) {
                if (sup instanceof TypeExpression.NameRef nr) {
                    work.add(nr.name());
                }
            }
        }
        return out;
    }

    /** One thread's subtype-dispatch columns — same order in every thread. */
    private static void addSubTypeDispatchCols(
            Map<String, LinkedHashSet<String>> subTypeProps,
            ClassMapping member, MappingNormalizer.RelationalParts pp,
            ModelBuilder model, List<ColSpec> cols) {
        for (var stEn : subTypeProps.entrySet()) {
            ClassDefinition subDef = model.findClass(stEn.getKey()).orElse(null);
            boolean own = isSubclassOf(member.className(), stEn.getKey(), model);
            for (String prop : stEn.getValue()) {
                if (prop.equals(MEMBER_WITNESS)) {
                    // toOne types both threads identically (literal vs NULL
                    // cast); lowering is erasure — the witness stays NULL
                    ValueSpecification w = own ? new CBoolean(true)
                            : new AppliedFunction("toOne", List.of(
                                    new AppliedFunction("cast", List.of(
                                            new PureCollection(List.of()),
                                            new TypeAnnotation.Named(
                                                    new TypeExpression.NameRef(
                                                            "Boolean"))))));
                    cols.add(new ColSpec(
                            ClassMapping.subTypeColumn(stEn.getKey(), prop),
                            new LambdaFunction(List.of(pp.rowBind()),
                                    List.of(w)), null));
                    continue;
                }
                KeyExpression mapped = own ? pp.fields().get(prop) : null;
                ValueSpecification value = mapped == null
                        ? MappingNormalizer.nullOfDeclaredType(subDef, prop, model)
                        : MappingNormalizer.coerceToDeclaredNumeric(
                                mapped.value(), prop, stEn.getKey(), model);
                TypeExpression dt = subDef == null ? null
                        : MappingNormalizer.findPropertyTypeDeep(subDef, prop, model);
                if (dt instanceof TypeExpression.NameRef dn
                        && "String".equals(MappingNormalizer.simpleTypeName(dn.name()))) {
                    value = new AppliedFunction("cast", List.of(value,
                            new TypeAnnotation.Named(
                                    new TypeExpression.NameRef("String"))));
                }
                value = new AppliedFunction("toOne", List.of(value));
                cols.add(new ColSpec(ClassMapping.subTypeColumn(stEn.getKey(), prop),
                        new LambdaFunction(List.of(pp.rowBind()),
                                List.of(value)), null));
            }
        }
    }

    /** The shared-property UNION ALL over resolved member sets. */
    static ValueSpecification synthMemberUnion(LegacyMappingDefinition md,
            String className, List<? extends ClassMapping> memberSets,
            ModelBuilder model) {
        List<MappingNormalizer.RelationalParts> parts = new ArrayList<>(memberSets.size());
        List<ClassMapping> members = new ArrayList<>(memberSets.size());
        for (ClassMapping cmIn : memberSets) {
            if (cmIn instanceof ClassMapping.RelationFunction rfm) {
                // Relation (~func) member: the parts are the inlined
                // relation body + its column reads — no main table, no
                // nav lifting (scalar columns only)
                Variable rfRow = new Variable("rf_row");
                Map<String, KeyExpression> rfFields = new LinkedHashMap<>();
                // the SAME extraction the single-set Relation path uses —
                // plain/enum/EMBEDDED/inline-embedded bindings all covered
                // (the embedded ^Inner values then distribute per sub-field
                // through the union's standard embedded machinery)
                MappingNormalizer.putRelationCols(rfFields, rfm.columns(),
                        rfRow, rfm.className(), md, model);
                members.add(rfm);
                parts.add(new MappingNormalizer.RelationalParts(
                        MappingNormalizer.relationFunctionPipeline(rfm, model), rfRow, rfFields));
                continue;
            }
            ClassMapping.Relational mr = (ClassMapping.Relational) cmIn;
            String setId = MappingNormalizer.setIdOf(mr);
            if (mr.sourceUrl() != null) {
                throw new NotImplementedException(
                        "Operation union over a JSON-source member set is not"
                      + " supported yet; mapping=" + md.qualifiedName());
            }
            if (mr.mainTable() == null) {
                LegacyMappingDefinition.TableReference inferred = MappingNormalizer.inferMainTable(mr);
                if (inferred == null) {
                    throw new NotImplementedException(
                            "union member set '" + setId + "' has no inferable"
                          + " main table; mapping=" + md.qualifiedName());
                }
                mr = new ClassMapping.Relational(mr.className(), mr.setId(),
                        mr.extendsSetId(), mr.root(), inferred, mr.filter(),
                        mr.distinct(), mr.groupBy(), mr.primaryKey(),
                        mr.propertyMappings(), mr.sourceUrl(),
                        mr.propertyTargetSets());
            }
            DatabaseDefinition.ViewDefinition memberView = model.findView(
                    mr.mainTable().database(), mr.mainTable().table()).orElse(null);
            if (memberView != null) {
                // VIEW-backed member set: the view expands as the member
                // thread's SOURCE SUBSELECT (engine unionOfViews golden —
                // each thread is `from (select ... from PersonExtensionT<i>)
                // as "root"`), the view name is the row scope, and PMs read
                // the view's DECLARED columns verbatim (the same subselect
                // treatment grouped view-backed class mappings get).
                ValueSpecification viewSource = ViewRelation.viewRelationExpr(
                        memberView, mr.mainTable().table(),
                        mr.mainTable().database(), model, md);
                members.add(mr);
                parts.add(MappingNormalizer.synthTableBackedParts(md, mr, model,
                        null, viewSource));
                continue;
            }
            members.add(mr);
            parts.add(MappingNormalizer.synthTableBackedParts(md, mr, model, null));
        }
        // the UNION of the members' scalar property sets, first-appearance
        // order — a member that does not map a property contributes a typed
        // NULL in its thread (engine: 'null as ...' / __SQLNULL__ columns;
        // partial-union reads come back TDSNull, testUnionPartial goldens)
        ClassDefinition owner = model.findClass(className).orElse(null);
        List<String> common = new ArrayList<>();
        for (MappingNormalizer.RelationalParts pp : parts) {
            for (String prop : pp.fields().keySet()) {
                TypeExpression t = owner == null ? null
                        : MappingNormalizer.findPropertyTypeDeep(owner, prop, model);
                boolean scalar = t instanceof TypeExpression.NameRef nr
                        && model.findClass(nr.name()).isEmpty();
                if (scalar && !common.contains(prop)) {
                    common.add(prop);
                }
            }
        }
        if (common.isEmpty()) {
            throw new NotImplementedException(
                    "Operation union members of '" + className
                  + "' map no scalar properties; mapping=" + md.qualifiedName());
        }
        // EMBEDDED PMs distribute per SUB-FIELD (engine union model): each
        // member thread projects its own embedded sub-columns under the
        // synthetic emb__<prop>__<sub> names (typed NULL in members that
        // don't map the sub — the partial-union mechanism); the union root
        // ctor recomposes ^Inner(...) over those columns, so the resolver's
        // existing EMBEDDED arm dispatches. Only THREAD-PROJECTABLE sub
        // values distribute (plain member-row reads / constants) — a sub
        // reading a hoisted join slot stays undistributed (loud downstream,
        // never a silently-wrong projection).
        EmbDist emb = collectEmbeddedDistribution(parts, owner, model);
        Map<String, LinkedHashSet<String>> embSubs = emb.subs();
        Map<String, String> embInner = emb.inner();
        LinkedHashSet<String> embTops = emb.tops();
        // ==== NAV LIFT (engine union model): the members' class-typed
        // single-hop Join PMs lift to ONE legacyNavigate ON THE UNION —
        // member i's thread carries its join keys member-suffixed
        // (<col>_<i>, NULL in the other threads) and the navigate condition
        // ORs the per-entry conditions (target side suffixed too when the
        // entry routes to a union member of the TARGET class). Downstream,
        // the union class then looks like any nav-slot class.
        List<NavLift> lifts = collectNavLifts(md, className, members, model);
        // ordinal -> (base column -> suffixed name): the source keys each
        // member thread projects (its own reads; typed NULL elsewhere)
        Map<Integer, Map<String, String>> srcKeysByOrdinal = new LinkedHashMap<>();
        Map<Integer, List<LiftChain>> chainsByOrdinal = new LinkedHashMap<>();
        for (NavLift lf : lifts) {
            for (var en : lf.srcKeysByOrdinal().entrySet()) {
                srcKeysByOrdinal.computeIfAbsent(en.getKey(),
                        k -> new LinkedHashMap<>()).putAll(en.getValue());
            }
            for (var en : lf.chainsByOrdinal().entrySet()) {
                chainsByOrdinal.computeIfAbsent(en.getKey(),
                        k -> new ArrayList<>()).addAll(en.getValue());
            }
        }
        // keys demanded by EXTERNAL routed navigations INTO this union
        // (audit 11: the union body carries every routed key with full
        // PROVENANCE — the resolver must never re-derive key meaning from
        // column-name patterns, a real column spelled like a suffix hijacked
        // the NULL thread)
        collectInboundRouteKeys(md, model,
                members.stream().map(MappingNormalizer::setIdOf).toList(),
                members, srcKeysByOrdinal, chainsByOrdinal);
        Map<String, LinkedHashSet<String>> subTypeProps =
                subTypeDispatchProps(className, members, parts, model);
        ValueSpecification union = null;
        int ordinal = -1;
        for (MappingNormalizer.RelationalParts pp : parts) {
            ordinal++;
            List<ColSpec> cols = new ArrayList<>(common.size());
            for (String prop : common) {
                // member sets may disagree on the COLUMN kind (String col in
                // set1, Integer expression in set2) and MULTIPLICITY (a
                // join-terminal read is [0..1], a plain column [1]) — the
                // declared property is the union's schema contract: numeric/
                // date kinds coerce, and a declared-[1] property wraps in
                // toOne (typing [1] on both sides; lowering is erasure)
                KeyExpression mapped = pp.fields().get(prop);
                ValueSpecification value = mapped == null
                        ? MappingNormalizer.nullOfDeclaredType(owner, prop, model)
                        : MappingNormalizer.coerceToDeclaredNumeric(
                                mapped.value(), prop, className, model);
                // String is safe INSIDE the union projection: the members
                // must agree on the declared kind, and the engine's union
                // coerces at the SQL boundary
                TypeExpression dt = owner == null ? null
                        : MappingNormalizer.findPropertyTypeDeep(owner, prop, model);
                if (dt instanceof TypeExpression.NameRef dn
                        && ("String".equals(MappingNormalizer.simpleTypeName(dn.name())))) {
                    value = new AppliedFunction("cast", List.of(value,
                            new TypeAnnotation.Named(
                                    new TypeExpression.NameRef("String"))));
                }
                // every member column aligns to [1] (toOne types both sides
                // identically; lowering is erasure — the union's SQL columns
                // are nullable regardless, engine parity)
                value = new AppliedFunction("toOne", List.of(value));
                cols.add(new ColSpec(prop, new LambdaFunction(
                        List.of(pp.rowBind()), List.of(value)), null));
            }
            addEmbeddedThreadCols(embSubs, embInner, pp, model, cols);
            addSubTypeDispatchCols(subTypeProps, members.get(ordinal), pp,
                    model, cols);
            // lifted-navigation source keys: this thread reads its OWN key
            // columns under their member-suffixed names; other ordinals'
            // keys are typed NULL (nullable — no toOne wrap)
            for (var en : srcKeysByOrdinal.entrySet()) {
                for (var key : en.getValue().entrySet()) {
                    ValueSpecification read = en.getKey() == ordinal
                            ? new AppliedProperty(pp.rowBind(), key.getKey())
                            : MappingNormalizer.nullOfPhysicalKind((ClassMapping.Relational)
                                    members.get(en.getKey()),
                                    key.getKey(), md, model);
                    // toOne types both threads identically (real read vs
                    // NULL cast); lowering is erasure — the key stays NULL
                    read = new AppliedFunction("toOne", List.of(read));
                    cols.add(new ColSpec(key.getValue(), new LambdaFunction(
                            List.of(pp.rowBind()), List.of(read)), null));
                }
            }
            // CHAINED entries: the owning thread wraps its pipeline in the
            // MID-hop joins and reads the final hop's source keys via the
            // last mid slot; other threads project a typed NULL of the mid
            // table's column kind (engine 3-sets golden: fk1_1 from a_0)
            ValueSpecification threadPipe = pp.pipeline();
            // two chained entries may share a mid hop (two lifted props
            // navigating through the same mid join): ONE slot serves both
            Set<String> wrapped = new LinkedHashSet<>();
            for (LiftChain ch : chainsByOrdinal.getOrDefault(ordinal,
                    Collections.emptyList())) {
                for (LiftMidStep st : ch.steps()) {
                    if (!wrapped.add(st.alias())) {
                        continue;
                    }
                    threadPipe = new AppliedFunction("join", List.of(threadPipe,
                            new ColSpec(st.alias(), new LambdaFunction(List.of(),
                                    List.of(ViewRelation.relationExpr(
                                            st.db(), st.table(), model, md))),
                                    null),
                            st.cond()));
                }
            }
            addChainedLiftCols(chainsByOrdinal, ordinal, pp, md, model, cols);
            ValueSpecification projected = new AppliedFunction("project",
                    List.of(threadPipe, new ColSpecArray(cols)));
            union = union == null ? projected
                    : new AppliedFunction("concatenate", List.of(union, projected));
        }
        // the lifted navigations sit ABOVE the concatenate — one slot per
        // property, exactly the standard nav-slot pipeline shape
        for (NavLift lf : lifts) {
            ColSpec slot = new ColSpec(lf.property(), new LambdaFunction(List.of(),
                    List.of(new AppliedFunction("getAll", List.of(
                            new PackageableElementPtr(lf.targetClassFqn()))))),
                    null);
            union = new AppliedFunction("legacyNavigate",
                    lf.pairedCondition() == null
                            ? List.of(union, slot, lf.targetRows(), lf.condition())
                            : List.of(union, slot, lf.targetRows(), lf.condition(),
                                    lf.pairedCondition()));
        }
        Variable row = new Variable("u_row");
        Map<String, KeyExpression> ctor = new LinkedHashMap<>();
        for (String prop : common) {
            ctor.put(prop, new KeyExpression(
                    new AppliedProperty(row, prop), false, false));
        }
        for (NavLift lf : lifts) {
            ctor.put(lf.property(), new KeyExpression(
                    new AppliedProperty(row, lf.property()), false, false));
        }
        for (String top : embTops) {
            ctor.put(top, new KeyExpression(
                    rebuildEmbCtor(top, embSubs, embInner, row, model),
                    false, false));
        }
        return new AppliedFunction("map", List.of(union,
                new LambdaFunction(List.of(row),
                        List.of(MappingNormalizer.buildNewInstanceToOne(className, ctor, model)))));
    }

    /** The embedded ctor under a field value: unwrap {@code toOne(...)}
     * then the parser/normalizer {@code new(ptr, NewInstance)} wrapper
     * (MappingNormalizer.buildNewInstance emission). Null = not a ctor. */
    private static @com.legend.Nullable NewInstance ctorOf(ValueSpecification v) {
        if (v instanceof AppliedFunction f && f.function().equals("toOne")
                && f.parameters().size() == 1) {
            v = f.parameters().get(0);
        }
        if (v instanceof AppliedFunction nf && nf.function().equals("new")
                && nf.parameters().size() == 2) {
            v = nf.parameters().get(1);
        }
        return v instanceof NewInstance ni ? ni : null;
    }

    /** THREAD-PROJECTABLE: plain member-row reads (depth-1 property over
     * the row binder), literals, and functions thereof. A deeper property
     * chain (a hoisted join-slot sub-row read) is NOT — projecting it in
     * the thread would need the slot materialized inside the thread. */
    private static boolean isThreadProjectable(ValueSpecification v,
            String rowVar) {
        return switch (v) {
            // one-hop ($row.col) or two-hop ($row.slot.col): an embedded
            // sub bound THROUGH a join reads its emitted pipeline slot —
            // the owning member's thread carries that join (the same
            // two-hop-body shape chained-lift key columns project)
            case AppliedProperty ap -> ap.receiver()
                    instanceof com.legend.model.spec.Variable rv
                    ? rv.name().equals(rowVar)
                    : ap.receiver() instanceof AppliedProperty inner
                            && inner.receiver()
                                    instanceof com.legend.model.spec.Variable rv2
                            && rv2.name().equals(rowVar);
            case AppliedFunction f -> f.parameters().stream()
                    .allMatch(x -> isThreadProjectable(x, rowVar));
            case com.legend.model.spec.Variable var2 -> false;
            case NewInstance ni -> false;
            default -> true;   // literals / annotations
        };
    }

    /** CHAINED lift entries' key columns for one thread: the owning
     * ordinal reads its last-mid-slot keys; other ordinals project typed
     * NULLs of the mid table's column kind (engine 3-sets golden). */
    private static void addChainedLiftCols(
            Map<Integer, List<LiftChain>> chainsByOrdinal, int ordinal,
            MappingNormalizer.RelationalParts pp, LegacyMappingDefinition md,
            ModelBuilder model, List<ColSpec> cols) {
        // two chains may demand the same suffixed key column (two lifted
        // props sharing one mid hop): ONE projection serves both
        Set<String> projected = new LinkedHashSet<>();
        for (var en : chainsByOrdinal.entrySet()) {
            for (LiftChain ch : en.getValue()) {
                for (var key : ch.keys().entrySet()) {
                    if (!projected.add(key.getValue())) {
                        continue;
                    }
                    ValueSpecification read;
                    if (en.getKey() == ordinal) {
                        read = new AppliedProperty(new AppliedProperty(
                                pp.rowBind(), ch.keyAlias()), key.getKey());
                    } else {
                        // view-aware: chained lifts land on VIEW mid tables
                        // too (unionOfViewsToViewToUnion)
                        String kind = ViewRelation.columnPureKind(
                                ch.keyDb(), ch.keyTable(), key.getKey(), model);
                        if (kind == null) {
                            throw new NotImplementedException(
                                    "chained union key column '" + key.getKey()
                                    + "' has no derivable pure kind on table '"
                                    + ch.keyTable() + "'; mapping="
                                    + md.qualifiedName());
                        }
                        read = new AppliedFunction("cast", List.of(
                                new PureCollection(List.of()),
                                new TypeAnnotation.Named(
                                        new TypeExpression.NameRef(kind))));
                    }
                    read = new AppliedFunction("toOne", List.of(read));
                    cols.add(new ColSpec(key.getValue(), new LambdaFunction(
                            List.of(pp.rowBind()), List.of(read)), null));
                }
            }
        }
    }

    /** The embedded distribution: dotted-path leaf sets ("firm" ->
     * {legalName}, "applicant.firm" -> {legalName}), per-path ctor classes
     * for the root recomposition, and top props in appearance order. A
     * top prop with ANY unprojectable leaf (join-slot sub-read) poisons
     * WHOLE — conservative, never a silently-wrong projection. */
    private record EmbDist(Map<String, LinkedHashSet<String>> subs,
            Map<String, String> inner, LinkedHashSet<String> tops) {
    }

    private static EmbDist collectEmbeddedDistribution(
            List<MappingNormalizer.RelationalParts> parts,
            @com.legend.Nullable ClassDefinition unionClass, ModelBuilder model) {
        Map<String, LinkedHashSet<String>> embSubs = new LinkedHashMap<>();
        Map<String, String> embInner = new LinkedHashMap<>();
        Set<String> poisoned = new LinkedHashSet<>();
        for (MappingNormalizer.RelationalParts pp : parts) {
            for (var fe : pp.fields().entrySet()) {
                // SUBTYPE-only embedded props (a member ctor field the
                // union class does not declare) belong to the stc subtype
                // dispatch, never the base recompose — distributing them
                // types ^Base(subProp=...) loudly (partial subtype family)
                if (unionClass == null || MappingNormalizer
                        .findPropertyTypeDeep(unionClass, fe.getKey(),
                                model) == null) {
                    continue;
                }
                NewInstance ni = ctorOf(fe.getValue().value());
                if (ni != null) {
                    collectEmbLeaves(fe.getKey(), fe.getKey(), ni,
                            pp.rowBind().name(), embSubs, embInner, poisoned);
                }
            }
        }
        for (String bad : poisoned) {
            embSubs.keySet().removeIf(k -> k.equals(bad)
                    || k.startsWith(bad + "."));
            embInner.keySet().removeIf(k -> k.equals(bad)
                    || k.startsWith(bad + "."));
        }
        LinkedHashSet<String> tops = new LinkedHashSet<>();
        for (String k : embSubs.keySet()) {
            tops.add(k.contains(".") ? k.substring(0, k.indexOf('.')) : k);
        }
        return new EmbDist(embSubs, embInner, tops);
    }

    /** One member thread's embedded sub-columns (schema-contract handling
     * mirrors the scalar threads: numeric coercion + String cast + toOne
     * alignment; absent members project typed NULLs). */
    private static void addEmbeddedThreadCols(
            Map<String, LinkedHashSet<String>> embSubs,
            Map<String, String> embInner,
            MappingNormalizer.RelationalParts pp, ModelBuilder model,
            List<ColSpec> cols) {
        for (var epe : embSubs.entrySet()) {
            String epath = epe.getKey();
            ClassDefinition inner = model.findClass(embInner.get(epath))
                    .orElse(null);
            NewInstance ector = ctorAtPath(pp.fields(), epath);
            for (String sub : epe.getValue()) {
                ValueSpecification sv = ector != null
                        && ector.properties().containsKey(sub)
                        ? ector.properties().get(sub).value()
                        : MappingNormalizer.nullOfDeclaredType(
                                inner, sub, model);
                sv = MappingNormalizer.coerceToDeclaredNumeric(
                        sv, sub, embInner.get(epath), model);
                TypeExpression sdt = inner == null ? null
                        : MappingNormalizer.findPropertyTypeDeep(
                                inner, sub, model);
                if (sdt instanceof TypeExpression.NameRef sdn
                        && "String".equals(MappingNormalizer
                                .simpleTypeName(sdn.name()))) {
                    sv = new AppliedFunction("cast", List.of(sv,
                            new TypeAnnotation.Named(
                                    new TypeExpression.NameRef("String"))));
                }
                sv = new AppliedFunction("toOne", List.of(sv));
                cols.add(new ColSpec(embCol(epath, sub),
                        new LambdaFunction(List.of(pp.rowBind()),
                                List.of(sv)), null));
            }
        }
    }

    /** Recursive leaf collection under dotted ctor paths; nested ctors
     * recurse, unprojectable leaves poison the whole TOP prop. */
    private static void collectEmbLeaves(String top, String pathKey,
            NewInstance ni, String rowVar,
            Map<String, LinkedHashSet<String>> embSubs,
            Map<String, String> embInner, Set<String> poisoned) {
        embInner.putIfAbsent(pathKey, ni.className());
        for (var pe : ni.properties().entrySet()) {
            NewInstance sub = ctorOf(pe.getValue().value());
            if (sub != null) {
                collectEmbLeaves(top, pathKey + "." + pe.getKey(), sub,
                        rowVar, embSubs, embInner, poisoned);
            } else if (isThreadProjectable(pe.getValue().value(), rowVar)) {
                embSubs.computeIfAbsent(pathKey, k -> new LinkedHashSet<>())
                        .add(pe.getKey());
            } else {
                poisoned.add(top);
            }
        }
    }

    /** The member's ctor at a dotted path, or null (member maps none). */
    private static @com.legend.Nullable NewInstance ctorAtPath(Map<String, KeyExpression> fields,
            String path) {
        String[] segs = path.split("\\.");
        KeyExpression ke = fields.get(segs[0]);
        NewInstance ni = ke == null ? null : ctorOf(ke.value());
        for (int i = 1; ni != null && i < segs.length; i++) {
            KeyExpression sub = ni.properties().get(segs[i]);
            ni = sub == null ? null : ctorOf(sub.value());
        }
        return ni;
    }

    /** The synthetic union column for one embedded leaf. */
    private static String embCol(String path, String sub) {
        return "emb__" + path.replace(".", "__") + "__" + sub;
    }

    /** Recompose the union root's embedded ctor from projected columns. */
    private static ValueSpecification rebuildEmbCtor(String path,
            Map<String, LinkedHashSet<String>> embSubs,
            Map<String, String> embInner, Variable row, ModelBuilder model) {
        Map<String, KeyExpression> fields = new LinkedHashMap<>();
        for (String sub : embSubs.getOrDefault(path, new LinkedHashSet<>())) {
            fields.put(sub, new KeyExpression(
                    new AppliedProperty(row, embCol(path, sub)), false, false));
        }
        for (String k : embInner.keySet()) {
            if (k.startsWith(path + ".") && k.indexOf('.', path.length() + 1) < 0) {
                fields.put(k.substring(path.length() + 1), new KeyExpression(
                        rebuildEmbCtor(k, embSubs, embInner, row, model),
                        false, false));
            }
        }
        return MappingNormalizer.buildNewInstanceToOne(
                embInner.get(path), fields, model);
    }

    /**
     * One lifted union navigation: the property, its target class extent,
     * the OR'd per-entry condition {@code {s,t|...}} (source reads
     * member-suffixed; target reads suffixed per routed target member),
     * the target-rows typing arg, and the per-ordinal source key columns
     * each member thread must carry.
     */
    record NavLift(String property, String targetClassFqn,
            ValueSpecification targetRows, LambdaFunction condition,
            @com.legend.Nullable LambdaFunction pairedCondition,
            Map<Integer, Map<String, String>> srcKeysByOrdinal,
            Map<Integer, List<LiftChain>> chainsByOrdinal) {
    }

    /** The MID hops of a chained lift entry as physical join steps,
     * prevAlias-scoped conditions composing hop to hop. */
    static ValueSpecification suffixTargetReads(ValueSpecification n,
            Variable t, int ord, Map<String, String> out) {
        return suffixTargetReads(n, t, "_" + ord, out);
    }

    /** Explicit-suffix variant: chained lifts scope their key names by
     * PROPERTY ({@code col__prop_ord}) so two chains of one member whose
     * mid tables share a column name never collide (V4: aT.fk1 vs
     * gT.fk1). All consumers read the names through {@code out} /
     * colspec-body provenance, never by pattern. */
    static ValueSpecification suffixTargetReads(ValueSpecification n,
            Variable t, String suffix, Map<String, String> out) {
        if (n instanceof AppliedProperty ap
                && ap.receiver() instanceof Variable v
                && v.name().equals(t.name())) {
            String suffixed = ap.property() + suffix;
            out.put(ap.property(), suffixed);
            return new AppliedProperty(v, suffixed);
        }
        return switch (n) {
            case AppliedFunction af -> new AppliedFunction(af.function(),
                    af.parameters().stream().map(x ->
                            suffixTargetReads(x, t, suffix, out)).toList());
            case AppliedProperty ap -> new AppliedProperty(
                    suffixTargetReads(ap.receiver(), t, suffix, out), ap.property());
            case Variable v -> v;
            case CString ignored -> n;
            case CInteger ignored -> n;
            case CFloat ignored -> n;
            case CDecimal ignored -> n;
            case CBoolean ignored -> n;
            case CDate ignored -> n;
            case PureCollection pc -> new PureCollection(pc.values().stream()
                    .map(x -> suffixTargetReads(x, t, suffix, out)).toList());
            default -> throw new NotImplementedException(
                    "partial-union route join condition carries a "
                    + n.getClass().getSimpleName()
                    + " — not suffixable yet");
        };
    }

    /**
     * THE chained-route classification (shared by the emitter and the
     * inbound key collector): TRUE when every member route walks the
     * IDENTICAL prefix (all hops but the last) — the shared-prefix model
     * where the prefix emits once as physical joins. FALSE (per-arm /
     * push-into-arm) when member routes diverge in length or mid joins:
     * each route's mids then live INSIDE the owning member's thread and
     * the navigate reads each route's FIRST hop.
     */
    static boolean uniformChainedRoutes(List<PropertyMapping.Join> memberJs) {
        if (memberJs.isEmpty()
                || memberJs.stream().noneMatch(j -> j.joins().size() > 1)) {
            return true;
        }
        PropertyMapping.Join first = memberJs.get(0);
        for (PropertyMapping.Join j : memberJs) {
            if (j.joins().size() != first.joins().size()) {
                return false;
            }
            for (int h = 0; h + 1 < j.joins().size(); h++) {
                JoinChainElement a = first.joins().get(h);
                JoinChainElement b = j.joins().get(h);
                String dbA = a.databaseName() != null
                        ? a.databaseName() : first.database();
                String dbB = b.databaseName() != null
                        ? b.databaseName() : j.database();
                if (!a.joinName().equals(b.joinName())
                        || !java.util.Objects.equals(dbA, dbB)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The union-member routes of a routed property (root/sole routes carry ordinal -1). */
    static List<PropertyMapping.Join> memberJoins(List<UnionRoute> routes) {
        return routes.stream().filter(r -> r.targetOrdinal() >= 0)
                .map(UnionRoute::join).toList();
    }

    /**
     * PER-ARM inbound mid steps (push-into-arm, engine
     * unionOfViews2/JoinSequenceInProperty behavior): the route's chain
     * walked from the TARGET member's table BACK through the mids (all
     * hops but the FIRST, reversed), each joined inside the member
     * thread. The route key the navigate reads is the FIRST hop's
     * landing-table columns, exposed under the PROPERTY-SCOPED suffixed
     * name ({@code col__prop_ord} — deterministic on BOTH the emission
     * and registration sides, and collision-free across a member's
     * chains) off the last emitted mid alias — the exact mirror of
     * {@link #liftMidSteps} for outbound lifts.
     */
    static List<LiftMidStep> inboundArmSteps(PropertyMapping.Join j,
            String prop, String memberTable, LegacyMappingDefinition md,
            ModelBuilder model) {
        List<LiftMidStep> steps = new ArrayList<>();
        String prevTable = memberTable;
        String prevAlias = null;
        for (int h = j.joins().size() - 1; h >= 1; h--) {
            JoinChainElement midHop = j.joins().get(h);
            String midDb = midHop.databaseName() != null
                    ? midHop.databaseName() : j.database();
            DatabaseDefinition.JoinDefinition mjd =
                    model.findJoin(midDb, midHop.joinName()).orElseThrow(() ->
                            new ModelException(
                                    LegendCompileException
                                            .Phase.NORMALIZE,
                                    "Join '" + midHop.joinName() + "' not"
                                    + " found in db '" + midDb + "'; PM='"
                                    + prop + "', mapping="
                                    + md.qualifiedName()));
            String midTgt = MappingNormalizer.determineTargetTable(mjd.operation(),
                    prevTable, midHop.joinName(), prop, h,
                    md.qualifiedName());
            Variable ms = new Variable("s");
            Variable mt = new Variable("t");
            Map<String, ValueSpecification> midScope = new LinkedHashMap<>();
            midScope.put(prevTable, prevAlias == null ? ms
                    : new AppliedProperty(ms, prevAlias));
            if (!midTgt.equals(prevTable)) {
                midScope.put(midTgt, mt);
            }
            ValueSpecification midCond = RelOpTranslator.translate(
                    mjd.operation(), midScope, mt, null,
                    RelOpTranslator.PipelineView.NONE);
            String midAlias = "nl__" + prop + "__inb__" + midHop.joinName();
            steps.add(new LiftMidStep(midAlias, midDb, midTgt,
                    new LambdaFunction(List.of(ms, mt),
                            List.of(midCond))));
            prevTable = midTgt;
            prevAlias = midAlias;
        }
        return steps;
    }

    /** The MERGED (un-suffixed target) lift eligibility — see the caller's
     * partiallyMilestoning-golden comment. SAME-JOIN across every route is
     * REQUIRED: merged and routed emissions only coincide when the join is
     * literally shared; diagonal routes (different joins per member —
     * graph rootLevel SameStore golden) demand strict member pairing. */
    private static boolean liftTargetMerged(List<int[]> ordsPre,
            List<PropertyMapping.Join> jsPre, String prop,
            ClassMapping.@com.legend.Nullable Union targetUnion, String targetClassFqn,
            List<ClassMapping> members, LegacyMappingDefinition md,
            ModelBuilder model) {
        if (targetUnion == null) {
            return false;
        }
        Set<Integer> tgtOrds = new HashSet<>();
        Set<Integer> srcMembers = new HashSet<>();
        Set<String> tgtColSets = new HashSet<>();
        boolean mergeable = true;
        for (int k2 = 0; mergeable && k2 < jsPre.size(); k2++) {
            PropertyMapping.Join j0 = jsPre.get(k2);
            if (j0.targetSetId() == null || j0.joins().size() != 1) {
                mergeable = false;
                break;
            }
            int o = memberOrdinalOf(targetUnion.memberSetIds(), md,
                    model, j0.targetSetId());
            if (o < 0 || !srcMembers.add(ordsPre.get(k2)[0])) {
                mergeable = false;   // 2 routes on one source member
                break;
            }
            tgtOrds.add(o);
            JoinChainElement hop0 = j0.joins().get(0);
            String db0 = hop0.databaseName() != null
                    ? hop0.databaseName() : j0.database();
            DatabaseDefinition.JoinDefinition jd0 =
                    model.findJoin(db0, hop0.joinName()).orElse(null);
            if (jd0 == null) {
                mergeable = false;
                break;
            }
            String srcT = ((ClassMapping.Relational)
                    members.get(ordsPre.get(k2)[0])).mainTable().table();
            String tgtT = MappingNormalizer.determineTargetTable(jd0.operation(), srcT,
                    hop0.joinName(), prop, 1, md.qualifiedName());
            Set<String> cols0 = new TreeSet<>();
            MappingNormalizer.collectColumnsOfTable(jd0.operation(), tgtT, cols0);
            tgtColSets.add(String.join(",", cols0));
        }
        // NOTE (graph rootLevel SameStore vs partiallyMilestoning): the
        // ENGINE's two subsystems disagree on this exact shape — its
        // RELATIONAL path (pureToSQLQuery golden, rows [2,2]) merges and
        // cross-matches; its GRAPH executor pairs strictly per member
        // (product=null). The merged form is therefore CORRECT here (this
        // lift feeds the relational navigate); the graph-side strict
        // pairing needs a SECOND (paired) condition carried alongside —
        // the dual-condition design banked in task #84. A same-target-
        // table narrowing was tried and reverted: it broke the
        // partiallyMilestoning trio whose golden demands the cross-match.
        return mergeable
                && tgtOrds.size() == targetUnion.memberSetIds().size()
                && colsProjectedByTarget(tgtColSets, targetClassFqn, model);
    }

    /** ENGINE SINGLE-SET TARGET ROUTING (memory inclusive-union-dupes-
     * analysis): when the TARGET class is NOT union-mapped, every member's
     * SINGLE-hop PM cites the SAME (db, join), and each member
     * set-qualifies its OWN private target set, the engine routes the
     * navigation to ONE implementation — the LAST member's binding;
     * earlier members contribute NULL through the crossing (inclusive
     * golden: 'null as prodFk_1' in member 0, rows [2] not [2,2]). A
     * UNION-mapped target OR a shared/unqualified target set keeps the
     * per-member OR dispatch (snapshot golden: the engine's own OR form;
     * VarReferenceWithUnion golden: both members' rows live). */
    private static boolean singleSetTargetCollapse(
            ClassMapping.@com.legend.Nullable Union targetUnion, List<PropertyMapping.Join> js) {
        if (targetUnion != null || js.size() < 2) {
            return false;
        }
        boolean distinctTargetSets = js.stream()
                .map(PropertyMapping.Join::targetSetId)
                .filter(java.util.Objects::nonNull)
                .distinct().count() == js.size();
        if (!distinctTargetSets) {
            return false;
        }
        return js.stream().allMatch(x -> {
            if (x.joins().size() != 1 || js.get(0).joins().size() != 1) {
                return false;
            }
            JoinChainElement hx = x.joins().get(0);
            JoinChainElement h0 = js.get(0).joins().get(0);
            String dbx = hx.databaseName() != null ? hx.databaseName()
                    : x.database();
            String db0 = h0.databaseName() != null ? h0.databaseName()
                    : js.get(0).database();
            return hx.joinName().equals(h0.joinName()) && dbx.equals(db0);
        });
    }

    static List<LiftMidStep> liftMidSteps(PropertyMapping.Join j,
            String prop, String srcTable, LegacyMappingDefinition md,
            ModelBuilder model) {
        List<LiftMidStep> midSteps = new ArrayList<>();
        String prevTable = srcTable;
        String prevAlias = null;
        for (int h = 0; h + 1 < j.joins().size(); h++) {
            JoinChainElement midHop = j.joins().get(h);
            String midDb = midHop.databaseName() != null
                    ? midHop.databaseName() : j.database();
            DatabaseDefinition.JoinDefinition mjd =
                    model.findJoin(midDb, midHop.joinName()).orElseThrow(() ->
                            new ModelException(
                                    LegendCompileException
                                            .Phase.NORMALIZE,
                                    "Join '" + midHop.joinName() + "' not"
                                    + " found in db '" + midDb + "'; PM='"
                                    + prop + "', mapping="
                                    + md.qualifiedName()));
            String midTgt = MappingNormalizer.determineTargetTable(mjd.operation(),
                    prevTable, midHop.joinName(), prop, h + 1,
                    md.qualifiedName());
            Variable ms = new Variable("s");
            Variable mt = new Variable("t");
            Map<String, ValueSpecification> midScope = new LinkedHashMap<>();
            midScope.put(prevTable, prevAlias == null ? ms
                    : new AppliedProperty(ms, prevAlias));
            if (!midTgt.equals(prevTable)) {
                midScope.put(midTgt, mt);
            }
            ValueSpecification midCond = RelOpTranslator.translate(
                    mjd.operation(), midScope, mt, null,
                    RelOpTranslator.PipelineView.NONE);
            String midAlias = "nl__" + prop + "__" + midHop.joinName();
            midSteps.add(new LiftMidStep(midAlias, midDb, midTgt,
                    new LambdaFunction(List.of(ms, mt),
                            List.of(midCond))));
            prevTable = midTgt;
            prevAlias = midAlias;
        }
        return midSteps;
    }

    /** One physical MID hop of a CHAINED lift entry, wrapped around the
     * owning member's thread pipeline ({@code join(pipe, ~alias:
     * tableReference, cond)} — engine: mid tables join INSIDE the member
     * thread, 3-sets golden). */
    record LiftMidStep(String alias, String db, String table,
            LambdaFunction cond) {
    }

    /** A chained entry's per-member material: the mid steps plus the FINAL
     * hop's source-key columns (on the LAST mid table, read via its slot
     * and projected member-suffixed — engine {@code fk1_1}). */
    record LiftChain(List<LiftMidStep> steps,
            @com.legend.Nullable String keyAlias,
            String keyDb, String keyTable, Map<String, String> keys) {
    }

    /** Collect the member class-typed Join PMs liftable onto the union. */
    /** Routed-lift target key typing colspecs: a key whose base column is
     * absent on the FIRST landing table types as a NULL cast of ITS OWN
     * landing table's column kind (audit 11: heterogeneous target key
     * names across routed members). */
    private static List<ColSpec> routedLiftKeySpecs(
            Map<String, String[]> tgtKeyCols, String landingDb,
            String landingTable, LegacyMappingDefinition md,
            ModelBuilder model) {
        List<ColSpec> keySpecs = new ArrayList<>();
        for (var en2 : tgtKeyCols.entrySet()) {
            Variable kr = new Variable("kr");
            String base = en2.getValue()[0];
            ValueSpecification read;
            if (ViewRelation.columnPureKind(landingDb, landingTable,
                    base, model) != null) {
                read = new AppliedProperty(kr, base);
            } else {
                String kind = ViewRelation.columnPureKind(
                        en2.getValue()[1], en2.getValue()[2], base, model);
                if (kind == null) {
                    throw new NotImplementedException(
                            "routed lift key column '" + base
                            + "' has no derivable pure kind on table '"
                            + en2.getValue()[2] + "'; mapping="
                            + md.qualifiedName());
                }
                read = new AppliedFunction("cast", List.of(
                        new PureCollection(List.of()),
                        new TypeAnnotation.Named(
                                new TypeExpression.NameRef(kind))));
            }
            keySpecs.add(new ColSpec(en2.getKey(), new LambdaFunction(
                    List.of(kr), List.of(read)), null));
        }
        return keySpecs;
    }

    /** MERGED target reads resolve against the union's PROJECTED row —
     * valid only when every read column IS a projected name (a mapped
     * value column like the partiallyMilestoning golden's {@code id}). A
     * RAW key ({@code fk}) takes the SUFFIXED NULL-crossed form, where
     * member pairing comes free: off-member suffixes read NULL, so only
     * same-member pairs match (engine sqlQueryMerging golden
     * {@code fk_0=fk_0 OR fk_1=fk_1}). */
    private static boolean colsProjectedByTarget(Set<String> tgtColSets,
            String targetClassFqn, ModelBuilder model) {
        if (tgtColSets.size() != 1) {
            return false;
        }
        ClassDefinition tgtOwner = model.findClass(targetClassFqn).orElse(null);
        for (String c : tgtColSets.iterator().next().split(",")) {
            if (c.isEmpty() || tgtOwner == null
                    || MappingNormalizer.findPropertyTypeDeep(
                            tgtOwner, c, model) == null) {
                return false;
            }
        }
        return true;
    }

    static List<NavLift> collectNavLifts(LegacyMappingDefinition md,
            String className, List<ClassMapping> members,
            ModelBuilder model) {
        // property -> per-member entries, member order
        Map<String, List<int[]>> found = new LinkedHashMap<>();
        Map<String, List<PropertyMapping.Join>> joins = new LinkedHashMap<>();
        for (int i = 0; i < members.size(); i++) {
            if (!(members.get(i) instanceof ClassMapping.Relational mr)) {
                continue;   // Relation(~func) members carry no Join PMs
            }
            ClassDefinition memberOwner = model.findClass(mr.className()).orElse(null);
            for (PropertyMapping pm : mr.propertyMappings()) {
                if (!(pm instanceof PropertyMapping.Join j)) {
                    continue;
                }
                TypeExpression pt = memberOwner == null ? null
                        : MappingNormalizer.findPropertyTypeDeep(memberOwner, pm.propertyName(), model);
                if (!(pt instanceof TypeExpression.NameRef pnr)
                        || model.findClass(pnr.name()).isEmpty()) {
                    continue;   // scalar join-terminal shapes stay member-local
                }
                found.computeIfAbsent(pm.propertyName(), k -> new ArrayList<>())
                        .add(new int[]{i});
                joins.computeIfAbsent(pm.propertyName(), k -> new ArrayList<>())
                        .add(j);
            }
        }
        List<NavLift> lifts = new ArrayList<>();
        for (String prop : found.keySet()) {
            ClassDefinition owner = model.findClass(className).orElse(null);
            TypeExpression pt = owner == null ? null
                    : MappingNormalizer.findPropertyTypeDeep(owner, prop, model);
            if (!(pt instanceof TypeExpression.NameRef pnr)
                    || !model.isMappedClass(pnr.name())) {
                continue;
            }
            String targetClassFqn = pnr.name();
            // BITEMPORAL UNGATE (Leg 2): the per-dimension stampers
            // (milestonedPipeByStrategy walks only tables CARRYING each
            // dimension) are capability-aware by construction after the
            // temporal-frame arc — the audit-11 gate that protected the
            // hybrid over-match (12 vs 18) is retired; the hybrid family
            // gates the rows.

            ClassMapping.Union targetUnion = unionForClass(md, model, targetClassFqn);
            // Pre-validate the property's entries: any unsupported or
            // unresolvable entry SKIPS the whole property's lift (poison
            // reason recorded; demanding the property fails loudly) —
            // audit 11: a partial lift matched the wrong members, a throw
            // here poisoned scalar-only union queries.
            String skipReason = null;
            for (PropertyMapping.Join j0 : java.util.Objects.requireNonNull(joins.get(prop))) {
                if (j0.targetSetId() != null && (targetUnion == null
                        || memberOrdinalOf(targetUnion.memberSetIds(), md,
                                model, j0.targetSetId()) < 0)) {
                    // a route naming the target's ROOT/SOLE set is the
                    // UN-routed navigation (engine rootClassMappingByClass;
                    // multipleChainedJoins V2: z[y1, z0] into single-set Z)
                    ClassMapping set = MappingNormalizer.findSetById(md, model, j0.targetSetId());
                    // <= 1 RETAINED (audit 23 probed-and-reverted): the
                    // V5 chained-union family routes into a class whose
                    // sets live in an INCLUDE (zero own-mapping sets) and
                    // pins the root-navigation degradation as row-correct.
                    boolean rootOrSole = set instanceof ClassMapping.Relational tr
                            && (tr.root() || md.classMappings().stream()
                                    .filter(x -> x.className().equals(tr.className()))
                                    .count() <= 1);
                    if (rootOrSole) {
                        continue;
                    }
                    // H5 SET-ID DISPATCH: a route naming a NON-root set of
                    // a multi-set non-union target is a single-target
                    // navigation to THAT set — the landing table below is
                    // already the set's own table, and the resolver's
                    // routedTargetSetOf hint materializes the set's
                    // binding (engine inclusive-milestoning union goldens
                    // join the named set's table directly).
                    if (set instanceof ClassMapping.Relational) {
                        continue;
                    }
                    skipReason = "route '[" + j0.targetSetId() + "]' that is"
                            + " not a member of the target class's union";
                    break;
                }
            }
            if (skipReason != null) {
                model.mappingPoisons.merge(
                        md.qualifiedName() + "::" + className,
                        "union navigation '" + prop + "' uses " + skipReason
                                + "; the property is not lifted",
                        (a, b) -> a + "; " + b);
                continue;
            }
            // MERGED (un-suffixed target) lift — the engine's cross-match
            // form, pinned by the partiallyMilestoning golden (source
            // members o1->p1, o2->p2; both joins read target column `id`;
            // ON prodFk_0 = id OR prodFk_1 = id; 2x2 rows asserted): fires
            // iff routes cover EVERY target member with exactly ONE route
            // PER SOURCE MEMBER and all entries read the SAME target
            // columns. A source member carrying routes to MULTIPLE target
            // members (unionToUnion: firm[f1] AND firm[f2] on each Person
            // set) keeps the per-pair suffixed form (testUnion golden
            // FirmID_0 = ID_0 OR FirmID_1 = ID_1 — audit 12: the merged
            // form cross-matched colliding keys, [0..1] fan-out).
            boolean liftTargetMerged = liftTargetMerged(found.get(prop),
                    joins.get(prop), prop, targetUnion, targetClassFqn,
                    members, md, model);
            Variable s = new Variable("s");
            Variable t = new Variable("t");
            ValueSpecification orCond = null;
            ValueSpecification orPaired = null;
            Map<String, String[]> pairedTgtKeyCols = new LinkedHashMap<>();
            // suffixed -> [base column, its route's db, its route's landing
            // table] (heterogeneous target key typing needs the provenance)
            Map<String, String[]> tgtKeyCols = new LinkedHashMap<>();
            String landingDb = null;
            String landingTable = null;
            Map<Integer, Map<String, String>> srcKeys = new LinkedHashMap<>();
            Map<Integer, List<LiftChain>> chains = new LinkedHashMap<>();
            List<int[]> ords = found.get(prop);
            List<PropertyMapping.Join> js = joins.get(prop);
            boolean sameJoin = singleSetTargetCollapse(targetUnion, js);
            for (int k = 0; k < js.size(); k++) {
                if (sameJoin && k < js.size() - 1) {
                    continue;
                }
                int memberOrd = ords.get(k)[0];
                PropertyMapping.Join j = js.get(k);
                String srcTable = ((ClassMapping.Relational)
                        members.get(memberOrd)).mainTable().table();
                // MID hops (all but the last): physical join steps around
                // the owning member's thread (engine: mids join INSIDE the
                // thread; the final hop is the union-level navigation)
                List<LiftMidStep> midSteps = liftMidSteps(j, prop, srcTable,
                        md, model);
                String prevTable = midSteps.isEmpty() ? srcTable
                        : midSteps.get(midSteps.size() - 1).table();
                String prevAlias = midSteps.isEmpty() ? null
                        : midSteps.get(midSteps.size() - 1).alias();
                JoinChainElement hop = j.joins().get(j.joins().size() - 1);
                String hopDb = hop.databaseName() != null ? hop.databaseName()
                        : j.database();
                DatabaseDefinition.JoinDefinition jd =
                        model.findJoin(hopDb, hop.joinName()).orElseThrow(() ->
                                new ModelException(
                                        LegendCompileException
                                                .Phase.NORMALIZE,
                                        "Join '" + hop.joinName() + "' not found"
                                        + " in db '" + hopDb + "'; PM='" + prop
                                        + "', mapping=" + md.qualifiedName()));
                String tgtTable = MappingNormalizer.determineTargetTable(jd.operation(), prevTable,
                        hop.joinName(), prop, j.joins().size(),
                        md.qualifiedName());
                if (landingTable == null) {
                    landingDb = hopDb;
                    landingTable = tgtTable;
                }
                Map<String, ValueSpecification> scope = new LinkedHashMap<>();
                scope.put(prevTable, s);
                if (!tgtTable.equals(prevTable)) {
                    scope.put(tgtTable, t);
                }
                ValueSpecification cond = RelOpTranslator.translate(
                        jd.operation(), scope, t, null,
                        RelOpTranslator.PipelineView.NONE);
                Map<String, String> srcOut = new LinkedHashMap<>();
                // chained lifts: property-scoped key names (col__prop_ord)
                // — two chains of ONE member may land on mid tables sharing
                // a column name (V4: aT.fk1 vs gT.fk1)
                cond = midSteps.isEmpty()
                        ? suffixTargetReads(cond, s, memberOrd, srcOut)
                        : suffixTargetReads(cond, s,
                                "__" + prop + "_" + memberOrd, srcOut);
                if (midSteps.isEmpty()) {
                    srcKeys.computeIfAbsent(memberOrd, x -> new LinkedHashMap<>())
                            .putAll(srcOut);
                } else {
                    chains.computeIfAbsent(memberOrd, x -> new ArrayList<>())
                            .add(new LiftChain(midSteps, prevAlias,
                                    midSteps.get(midSteps.size() - 1).db(),
                                    prevTable, srcOut));
                }
                Integer tgtOrd = j.targetSetId() != null && targetUnion != null
                        ? memberOrdinalOf(targetUnion.memberSetIds(), md,
                                model, j.targetSetId())
                        : null;
                // the PAIRED (member-suffixed target) variant builds
                // ALWAYS; the emitted predicate is the MERGED (raw-target)
                // form only when liftTargetMerged — the paired variant
                // then rides alongside for GRAPH children, whose engine
                // subsystem pairs strictly (TypedNavigate.pairedPredicate)
                ValueSpecification pairedEntry = cond;
                if (tgtOrd != null && tgtOrd >= 0) {
                    Map<String, String> tgtOut = new LinkedHashMap<>();
                    pairedEntry = suffixTargetReads(cond, t, tgtOrd, tgtOut);
                    // merged lifts park their suffixed keys aside — they
                    // join the typing keySpecs ONLY if the paired lambda
                    // actually rides (the merged emission itself must stay
                    // byte-identical to the pre-paired form)
                    for (var en2 : tgtOut.entrySet()) {
                        (liftTargetMerged ? pairedTgtKeyCols : tgtKeyCols)
                                .put(en2.getValue(),
                                        new String[]{en2.getKey(), hopDb, tgtTable});
                    }
                }
                if (!liftTargetMerged) {
                    cond = pairedEntry;
                }
                orCond = orCond == null ? cond
                        : new AppliedFunction("or", List.of(orCond, cond));
                orPaired = orPaired == null ? pairedEntry
                        : new AppliedFunction("or", List.of(orPaired, pairedEntry));
            }
            LambdaFunction pairedLam = liftTargetMerged && orPaired != null
                    && orPaired != orCond
                    ? new LambdaFunction(List.of(s, t), List.of(orPaired))
                    : null;
            if (pairedLam != null) {
                tgtKeyCols.putAll(pairedTgtKeyCols);
                // the MERGED predicate still reads the RAW final-hop
                // columns — the keySpecs projection must pass them through
                // alongside the suffixed pairs
                for (String[] v : pairedTgtKeyCols.values()) {
                    tgtKeyCols.putIfAbsent(v[0], new String[]{v[0], v[1], v[2]});
                }
            }
            ValueSpecification targetRows = ViewRelation.relationExpr(
                    java.util.Objects.requireNonNull(landingDb), java.util.Objects.requireNonNull(landingTable), model, md);
            if (!tgtKeyCols.isEmpty()) {
                List<ColSpec> keySpecs = routedLiftKeySpecs(tgtKeyCols,
                        java.util.Objects.requireNonNull(landingDb), java.util.Objects.requireNonNull(landingTable), md, model);
                targetRows = new AppliedFunction("project",
                        List.of(targetRows, new ColSpecArray(keySpecs)));
            }
            lifts.add(new NavLift(prop, targetClassFqn, targetRows,
                    new LambdaFunction(List.of(s, t), List.of(orCond)),
                    pairedLam, srcKeys, chains));
        }
        return lifts;
    }

    /**
     * Scan the mapping closure (own + includes) for routed Join PMs whose
     * target set is one of this union's members; add each route's
     * MEMBER-side key columns to {@code sink} as
     * ordinal &rarr; (base &rarr; {@code base_ordinal}) so the union body
     * projects them with full provenance.
     */
    static void collectInboundRouteKeys(LegacyMappingDefinition md,
            ModelBuilder model, List<String> memberIds,
            List<ClassMapping> members,
            Map<Integer, Map<String, String>> sink) {
        collectInboundRouteKeys(md, model, memberIds, members, sink, null);
    }

    static void collectInboundRouteKeys(LegacyMappingDefinition md,
            ModelBuilder model, List<String> memberIds,
            List<ClassMapping> members,
            Map<Integer, Map<String, String>> sink,
            @com.legend.Nullable Map<Integer, List<LiftChain>> chainsSink) {
        List<LegacyMappingDefinition> closure = new ArrayList<>();
        MappingNormalizer.collectMappingClosure(md, model, closure, new HashSet<>());
        for (LegacyMappingDefinition m : closure) {
            for (ClassMapping cm : m.classMappings()) {
                if (!(cm instanceof ClassMapping.Relational rcm)) {
                    continue;
                }
                // group per property: chained-route shape (shared-prefix
                // vs per-arm) is a PER-PROPERTY judgment over its member
                // routes — the same uniformChainedRoutes predicate the
                // emitter applies
                Map<String, List<PropertyMapping.Join>> byProp =
                        new LinkedHashMap<>();
                for (PropertyMapping pm : rcm.propertyMappings()) {
                    if (pm instanceof PropertyMapping.Join j
                            && j.targetSetId() != null) {
                        byProp.computeIfAbsent(j.propertyName(),
                                k -> new ArrayList<>()).add(j);
                    }
                }
                for (List<PropertyMapping.Join> group : byProp.values()) {
                    Map<PropertyMapping.Join, Integer> ords =
                            new LinkedHashMap<>();
                    for (PropertyMapping.Join j : group) {
                        int ord = memberOrdinalOf(memberIds, md, model,
                                j.targetSetId());
                        if (ord >= 0) {
                            ords.put(j, ord);
                        }
                    }
                    boolean uniform = uniformChainedRoutes(
                            List.copyOf(ords.keySet()));
                    for (var en : ords.entrySet()) {
                        registerInboundEntry(en.getKey(), en.getValue(),
                                members, uniform, md, model, sink,
                                chainsSink);
                    }
                }
            }
            // per-pair ASSOCIATION entries route INTO this union too: the
            // FINAL hop's target-side columns (on the routed member's main
            // table) ride suffixed — the lifted navigation's condition
            // reads them (multipleChainedJoins: t.fk_1)
            for (AssociationMapping am : m.associationMappings()) {
                if (!(am instanceof AssociationMapping.Relational rel)) {
                    continue;
                }
                // group per (source set, property): pair entries of ONE
                // navigation judge chain-shape together, exactly like the
                // class-PM arm above
                Map<String, Map<PropertyMapping.Join, Integer>> byProp =
                        new LinkedHashMap<>();
                for (AssociationPropertyMapping apm : rel.propertyMappings()) {
                    if (!(apm.body() instanceof PropertyMapping.Join j)) {
                        continue;
                    }
                    String tgtSet = j.targetSetId() != null
                            ? j.targetSetId() : apm.targetSetId();
                    if (tgtSet == null) {
                        continue;
                    }
                    int ord = memberOrdinalOf(memberIds, md, model, tgtSet);
                    if (ord < 0) {
                        continue;
                    }
                    byProp.computeIfAbsent(apm.sourceSetId() + "\u0000"
                            + apm.propertyName(), k -> new LinkedHashMap<>())
                            .put(j, ord);
                }
                for (Map<PropertyMapping.Join, Integer> ords
                        : byProp.values()) {
                    boolean uniform = uniformChainedRoutes(
                            List.copyOf(ords.keySet()));
                    for (var en : ords.entrySet()) {
                        registerInboundEntry(en.getKey(), en.getValue(),
                                members, uniform, md, model, sink,
                                chainsSink);
                    }
                }
            }
        }
    }

    /**
     * One inbound routed entry's key registration: EVERY route (uniform
     * shared-prefix AND per-arm) dispatches on its FINAL hop — the navigate
     * reads the last hop's member-table columns suffixed, and per-arm
     * routes join their own mid prefix OUTSIDE the union on the SOURCE
     * side (engine V4 golden: A/G joined outside, member keys plain).
     */
    private static void registerInboundEntry(PropertyMapping.Join j, int ord,
            List<ClassMapping> members, boolean uniform,
            LegacyMappingDefinition md, ModelBuilder model,
            Map<Integer, Map<String, String>> sink,
            @com.legend.Nullable Map<Integer, List<LiftChain>> chainsSink) {
        if (!(members.get(ord) instanceof ClassMapping.Relational routedMember)) {
            return;     // routes into Relation(~func) members have no
                        // physical key table (loud at navigation if demanded)
        }
        String memberTable = routedMember.mainTable().table();
        if (!uniform && j.joins().size() > 1 && chainsSink != null) {
            List<LiftMidStep> steps = inboundArmSteps(j, j.propertyName(),
                    memberTable, md, model);
            LiftMidStep landing = steps.get(steps.size() - 1);
            JoinChainElement firstHop = j.joins().get(0);
            String fdb = firstHop.databaseName() != null
                    ? firstHop.databaseName() : j.database();
            DatabaseDefinition.JoinDefinition fjd =
                    model.findJoin(fdb, firstHop.joinName()).orElse(null);
            if (fjd == null) {
                return;     // loud at the route's own emission
            }
            Set<String> fcols = new LinkedHashSet<>();
            MappingNormalizer.collectColumnsOfTable(fjd.operation(),
                    landing.table(), fcols);
            Map<String, String> keys = new LinkedHashMap<>();
            for (String c : fcols) {
                keys.put(c, c + "__" + j.propertyName() + "_" + ord);
            }
            List<LiftChain> have = chainsSink.computeIfAbsent(ord,
                    k -> new ArrayList<>());
            // the closure may surface the same route twice (class PM +
            // association pair) — one projection per suffixed key
            boolean dup = have.stream().anyMatch(ch -> ch.keys().values()
                    .stream().anyMatch(keys.values()::contains));
            if (!dup && !keys.isEmpty()) {
                have.add(new LiftChain(steps, landing.alias(), landing.db(),
                        landing.table(), keys));
            }
            return;
        }
        JoinChainElement hop = j.joins().get(j.joins().size() - 1);
        String db = hop.databaseName() != null
                ? hop.databaseName() : j.database();
        DatabaseDefinition.JoinDefinition jd =
                model.findJoin(db, hop.joinName()).orElse(null);
        if (jd == null) {
            return;     // loud at the route's own emission
        }
        Set<String> cols = new LinkedHashSet<>();
        MappingNormalizer.collectColumnsOfTable(jd.operation(), memberTable, cols);
        // self-join hops spell the member side {target}.col
        MappingNormalizer.collectTargetColumns(jd.operation(), cols);
        for (String c : cols) {
            sink.computeIfAbsent(ord, k -> new LinkedHashMap<>())
                    .put(c, c + "_" + ord);
        }
    }
}
