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
/**
 * Association-mapping realization: multi-hop end injection (Option A), per-pair entry gathering, predicate-function synthesis (doc 5.6.1). Split from MappingNormalizer (the Doors split).
 */
final class AssociationSynthesis {

    private AssociationSynthesis() {}

    /**
     * Option A ({@code docs/MAPPING_LEGACY_TO_FUNCTION.md} §5.6.1b): a multi-hop
     * association end is realized as a class-typed Join PM on the end's
     * owning class, flowing through the same join-chain machinery as a
     * class-typed property mapping ({@link #emitJoinChain}). This pre-pass
     * appends those Join PMs to the relevant {@link ClassMapping.Relational}
     * before class-mapping synthesis. Single-hop ends are left to the
     * standalone {@code legacyAssocPredicate} path (§5.6.1).
     */
    static LegacyMappingDefinition injectMultiHopAssociationPMs(LegacyMappingDefinition md,
                                                                 ModelBuilder model) {
        // INCLUDE-CLOSURE: the association entries, the owning class
        // mappings and the target unions may live in DIFFERENT mapping
        // definitions (multipleChainedJoins V4: top mapping = unions only,
        // includes carry the class sets and the pair entries). Navigation
        // behavior depends on the REQUESTING mapping's context, so the
        // top-level normalization injects — hoisting an included owner
        // set into this definition when needed (the hoisted copy shadows
        // the included one for this mapping's synthesis).
        List<LegacyMappingDefinition> closure = new ArrayList<>();
        MappingNormalizer.collectMappingClosure(md, model, closure, new HashSet<>());
        List<AssociationMapping.Relational> rels = new ArrayList<>();
        for (LegacyMappingDefinition m : closure) {
            for (AssociationMapping am : m.associationMappings()) {
                if (am instanceof AssociationMapping.Relational r) {
                    rels.add(r);
                }
            }
        }
        if (rels.isEmpty()) return md;
        // className -> unqualified injections; className -> setId -> per-set
        // injections (pair entries bind to their SOURCE set only)
        Map<String, List<PropertyMapping>> byClass = new LinkedHashMap<>();
        Map<String, Map<String, List<PropertyMapping>>> bySet = new LinkedHashMap<>();
        for (AssociationMapping.Relational rel : rels) {
            AssociationDefinition ad = model.findAssociation(rel.associationName()).orElse(null);
            if (ad == null) continue;
            // per (source set, property): a pair group whose TARGET class
            // is union-mapped and which carries ANY chained entry is a
            // ROUTED-UNION group — ALL its entries (single-hop included)
            // inject as target-stamped Join PMs so classifyUnionRoutes
            // sees the full route set and dispatches per arm (V4
            // push-into-arm). Pure single-hop groups stay on the
            // predicate path (U3).
            Map<String, Boolean> routedUnionGroups = new LinkedHashMap<>();
            for (AssociationPropertyMapping apm : rel.propertyMappings()) {
                if (!(apm.body() instanceof PropertyMapping.Join join)
                        || apm.sourceSetId() == null) {
                    continue;
                }
                String target = associationTargetClass(ad, apm.propertyName());
                if (target == null) {
                    continue;
                }
                boolean unionTgt =
                        UnionSynthesis.unionForClass(md, model, target) != null;
                boolean inheritanceTgt = !unionTgt
                        && UnionSynthesis.inheritanceForClass(md, model, target)
                                != null;
                if (!unionTgt && !inheritanceTgt) {
                    continue;
                }
                // an INHERITANCE-op target has NO set of its own class —
                // the predicate path cannot anchor it (no ~mainTable);
                // pair groups into it ALWAYS take the routed-PM injection
                // (FIX-A computes inheritance member ordinals)
                // The predicate path is IMPOSSIBLE when either end class
                // has no Relational set of its own class (union over
                // SUBCLASS sets: VehicleOwner union(airline,per1)) —
                // those groups force the routed-PM injection too.
                String owner0 = associationOwnerClass(ad, apm.propertyName());
                boolean bindingPossible = owner0 != null
                        && MappingNormalizer.hasMainTable(md, owner0, model)
                        && MappingNormalizer.hasMainTable(md, target, model);
                routedUnionGroups.merge(apm.sourceSetId() + "\u0000"
                        + apm.propertyName(),
                        inheritanceTgt || join.joins().size() > 1
                                || !bindingPossible,
                        Boolean::logicalOr);
            }
            for (AssociationPropertyMapping apm : rel.propertyMappings()) {
                if (!(apm.body() instanceof PropertyMapping.Join join)) continue;
                boolean routedUnion = apm.sourceSetId() != null
                        && Boolean.TRUE.equals(routedUnionGroups.get(
                                apm.sourceSetId() + "\u0000" + apm.propertyName()));
                if (join.joins().size() < 2 && !routedUnion) {
                    continue;   // single-hop -> predicate path
                }
                String owner = associationOwnerClass(ad, apm.propertyName());
                if (owner == null) continue;
                if (apm.sourceSetId() != null
                        && UnionSynthesis.unionForClass(md, model, owner) != null
                        && MappingNormalizer.findSetById(md, model,
                                apm.sourceSetId()) instanceof ClassMapping src0
                        && src0.className().equals(owner)) {
                    // per-pair entries on a UNION-mapped owner land on their
                    // member set at union synthesis instead
                    // (collectPairAssociationEntries, include-closure aware).
                    // A SUBCLASS member set (VehicleOwner union(airline,
                    // per1) where per1 maps Person) injects HERE too — it
                    // is independently queryable and the union-body copy
                    // never reaches its own ClassSource; the union arm's
                    // pmIdentity dedup absorbs the duplicate.
                    continue;
                }
                String tgtSet = join.targetSetId() != null
                        ? join.targetSetId() : apm.targetSetId();
                PropertyMapping.Join stamped = routedUnion && tgtSet != null
                        && join.targetSetId() == null
                        ? new PropertyMapping.Join(join.propertyName(),
                                join.database(), join.joins(), tgtSet)
                        : join;
                if (apm.sourceSetId() != null) {
                    bySet.computeIfAbsent(owner, k -> new LinkedHashMap<>())
                            .computeIfAbsent(apm.sourceSetId(),
                                    k -> new ArrayList<>()).add(stamped);
                } else {
                    byClass.computeIfAbsent(owner, k -> new ArrayList<>())
                            .add(stamped);
                }
            }
        }
        if (byClass.isEmpty() && bySet.isEmpty()) return md;
        List<ClassMapping> rewritten = new ArrayList<>(md.classMappings().size());
        Set<String> ownClasses = new HashSet<>();
        for (ClassMapping cm : md.classMappings()) {
            ownClasses.add(cm.className());
            ClassMapping.Relational injectedCm = withInjectedPMs(cm, byClass, bySet);
            rewritten.add(injectedCm != null ? injectedCm : cm);
        }
        // HOIST: an owner set that lives only in an INCLUDED definition is
        // copied up with its injections (skipped when this definition
        // already maps the class — notably union-rooted classes, whose
        // routes land at union synthesis instead)
        Set<String> hoisted = new HashSet<>();
        for (LegacyMappingDefinition m : closure) {
            if (m == md) continue;
            for (ClassMapping cm : m.classMappings()) {
                if (ownClasses.contains(cm.className())
                        || !hoisted.add(MappingNormalizer.setIdOf(cm))) {
                    continue;
                }
                ClassMapping.Relational injectedCm = withInjectedPMs(cm, byClass, bySet);
                if (injectedCm != null) {
                    rewritten.add(injectedCm);
                }
            }
        }
        return md.withClassMappings(rewritten);
    }

    /** The class mapping with this class's/set's pending injections
     * appended; null when none apply. */
    private static ClassMapping.@com.legend.Nullable Relational withInjectedPMs(ClassMapping cm,
            Map<String, List<PropertyMapping>> byClass,
            Map<String, Map<String, List<PropertyMapping>>> bySet) {
        if (!(cm instanceof ClassMapping.Relational rcm)) return null;
        List<PropertyMapping> add = new ArrayList<>();
        List<PropertyMapping> forClass = byClass.get(rcm.className());
        if (forClass != null) add.addAll(forClass);
        // per-SET injections match by SET ID under any owner key: the
        // association end's owner may be a SUPERCLASS of the set's class
        // (ownedVehicles on VehicleOwner, set per1 maps Person — the
        // sourceSetId pins the exact set; set ids are unique in scope)
        for (Map<String, List<PropertyMapping>> sets : bySet.values()) {
            List<PropertyMapping> forSet = sets.get(MappingNormalizer.setIdOf(rcm));
            if (forSet != null) add.addAll(forSet);
        }
        // EMBEDDED-set sources: an entry keyed <thisSetId>_<embProp> (or
        // the default <classFqnUnderscored>_<embProp>) belongs INSIDE this
        // set's embedded block — location[f1_address, loc] appends to
        // Firm[f1]'s address block, whose join chain then hoists through
        // the ordinary embedded sub-PM machinery.
        List<PropertyMapping> pms = new ArrayList<>(rcm.propertyMappings());
        boolean nested = false;
        String sid = MappingNormalizer.setIdOf(rcm);
        String classId = rcm.className().replace("::", "_");
        for (Map<String, List<PropertyMapping>> anySets : bySet.values()) {
            for (var en : anySets.entrySet()) {
                String key = en.getKey();
                String prop = key.startsWith(sid + "_")
                        ? key.substring(sid.length() + 1)
                        : key.startsWith(classId + "_")
                                ? key.substring(classId.length() + 1) : null;
                if (prop == null) {
                    continue;
                }
                for (int i = 0; i < pms.size(); i++) {
                    if (pms.get(i) instanceof PropertyMapping.Embedded emb
                            && emb.propertyName().equals(prop)) {
                        List<PropertyMapping> sub =
                                new ArrayList<>(emb.propertyMappings());
                        sub.addAll(en.getValue());
                        pms.set(i, new PropertyMapping.Embedded(
                                emb.propertyName(), sub));
                        nested = true;
                    }
                }
            }
        }
        if (add.isEmpty() && !nested) return null;
        pms.addAll(add);
        return new ClassMapping.Relational(
                rcm.className(), rcm.setId(), rcm.extendsSetId(), rcm.root(),
                rcm.mainTable(), rcm.filter(), rcm.distinct(), rcm.groupBy(),
                rcm.primaryKey(), pms, rcm.sourceUrl(),
                rcm.propertyTargetSets());
    }

    /**
     * SET-QUALIFIED per-pair AssociationMapping entries
     * ({@code y[x1, y1]: [db]@X1_A > @A_Y1} — multipleChainedJoins) whose
     * OWNING end is union class {@code classFqn}, gathered across the
     * INCLUDE CLOSURE (the entries, the members and the union operation
     * may live in different mapping definitions), keyed by the owning
     * member's set id with the target route stamped on the Join.
     */
    static void collectPairAssociationEntries(LegacyMappingDefinition md,
            ModelBuilder model, String classFqn,
            Map<String, List<PropertyMapping.Join>> out, Set<String> seen) {
        if (!seen.add(md.qualifiedName())) {
            return;
        }
        for (AssociationMapping am : md.associationMappings()) {
            if (!(am instanceof AssociationMapping.Relational rel)) continue;
            AssociationDefinition ad =
                    model.findAssociation(am.associationName()).orElse(null);
            if (ad == null) continue;
            for (AssociationPropertyMapping apm : rel.propertyMappings()) {
                if (!(apm.body() instanceof PropertyMapping.Join join)
                        || apm.sourceSetId() == null) {
                    continue;
                }
                String owner = associationOwnerClass(ad, apm.propertyName());
                // the union class may INHERIT the end (B extends A picking
                // up AE's 'e' — extends/union family): owner-or-superclass
                if (owner == null || !(owner.equals(classFqn)
                        || UnionSynthesis.isSubclassOf(classFqn, owner, model))) {
                    continue;
                }
                PropertyMapping.Join stamped = join.targetSetId() == null
                        ? new PropertyMapping.Join(join.propertyName(),
                                join.database(), join.joins(), apm.targetSetId())
                        : join;
                out.computeIfAbsent(apm.sourceSetId(), k -> new ArrayList<>())
                        .add(stamped);
            }
        }
        for (MappingInclude inc : md.includes()) {
            LegacyMappingDefinition inner =
                    model.findLegacyMapping(inc.mappingPath()).orElse(null);
            if (inner != null) {
                collectPairAssociationEntries(inner, model, classFqn, out, seen);
            }
        }
    }

    /**
     * The class that <em>owns</em> association property {@code propName}: in
     * {@code Association(p1: B, p2: A)}, property {@code p1} is declared on the
     * class {@code p2} points at (and vice versa). Returns {@code null} if
     * {@code propName} is neither end, or the opposite end is non-NameRef.
     */
    /** The property's OWN end class (the navigation target), mirror of
     * {@link #associationOwnerClass}. */
    static @com.legend.Nullable String associationTargetClass(AssociationDefinition ad, String propName) {
        if (ad.property1().propertyName().equals(propName)) {
            return MappingNormalizer.nameRefOrNull(ad.property1().targetClass());
        }
        if (ad.property2().propertyName().equals(propName)) {
            return MappingNormalizer.nameRefOrNull(ad.property2().targetClass());
        }
        return null;
    }

    static @com.legend.Nullable String associationOwnerClass(AssociationDefinition ad, String propName) {
        if (ad.property1().propertyName().equals(propName)) {
            return MappingNormalizer.nameRefOrNull(ad.property2().targetClass());
        }
        if (ad.property2().propertyName().equals(propName)) {
            return MappingNormalizer.nameRefOrNull(ad.property1().targetClass());
        }
        return null;
    }

    /** The mapped association, resolved through the MAPPING's import
     * scope when the header spells a SIMPLE name (the engine grammar:
     * {@code Driver : Relational { AssociationMapping (...) }} inside a
     * file importing the model package). */
    static java.util.Optional<AssociationDefinition> resolveAssociation(
            ModelBuilder model, LegacyMappingDefinition md,
            AssociationMapping am) {
        String name = am.associationName();
        var direct = model.findAssociation(name);
        if (direct.isPresent() || name.contains("::")) {
            return direct;
        }
        for (String pkg : model.importsOf(md.qualifiedName()).wildcards()) {
            var hit = model.findAssociation(pkg + "::" + name);
            if (hit.isPresent()) {
                return hit;
            }
        }
        // same-package fallback (an unimported sibling)
        int cut = md.qualifiedName().lastIndexOf("::");
        return cut < 0 ? java.util.Optional.empty()
                : model.findAssociation(
                        md.qualifiedName().substring(0, cut) + "::" + name);
    }

    static @com.legend.Nullable FunctionDefinition synthesizeAssociationMapping(LegacyMappingDefinition md,
                                                                  AssociationMapping am,
                                                                  ModelBuilder model) {
        AssociationDefinition ad0 = resolveAssociation(model, md, am)
                .orElse(null);
        if (am instanceof AssociationMapping.ModelJoin mj && ad0 != null) {
            return MappingNormalizer.synthesizeModelJoinMapping(md, mj, model,
                    ad0.property1().targetClassFqn(),
                    ad0.property2().targetClassFqn());
        }
        if (am instanceof AssociationMapping.Cross xs && ad0 != null) {
            return MappingNormalizer.synthesizeXStoreMapping(md, xs, model,
                    ad0.property1().targetClassFqn(),
                    ad0.property2().targetClassFqn());
        }
        if (!(am instanceof AssociationMapping.Relational rel)) {
            throw new NotImplementedException(
                    "Association mapping kind " + am.getClass().getSimpleName()
                  + " not supported; mapping=" + md.qualifiedName());
        }
        AssociationDefinition ad = resolveAssociation(model, md, am)
                .orElseThrow(() -> new ModelException(LegendCompileException.Phase.NORMALIZE, 
                        "AssociationMapping references unknown association '"
                      + am.associationName() + "'; mapping=" + md.qualifiedName()));
        String classA = ad.property1().targetClassFqn();
        String classB = ad.property2().targetClassFqn();

        if (rel.propertyMappings().isEmpty()) {
            throw new ModelException(LegendCompileException.Phase.NORMALIZE, 
                    "AssociationMapping for '" + am.associationName()
                  + "' has no property mappings; mapping=" + md.qualifiedName());
        }
        // Pick the FIRST property mapping as the primary; multi-PM
        // disambiguation by [srcSetId, tgtSetId] could differentiate
        // direction-specific joins but the predicate condition is the
        // same regardless (it describes the association in one place).
        AssociationPropertyMapping firstAm = rel.propertyMappings().get(0);
        if (!(firstAm.body() instanceof PropertyMapping.Join firstJoin)) {
            throw new NotImplementedException(
                    "AssociationMapping property body kind "
                  + firstAm.body().getClass().getSimpleName()
                  + " not supported (only Join bodies are bridged); mapping="
                  + md.qualifiedName()
                  + ". See docs/MAPPING_LEGACY_TO_FUNCTION.md §5.6.");
        }
        // Multi-hop association: realized as per-end navigation injected into
        // the class realizing functions (Option A; see
        // docs/MAPPING_LEGACY_TO_FUNCTION.md §5.6.1b). A (A,B)->Boolean predicate
        // cannot bind the intermediate row, so no standalone predicate is
        // emitted — return null and let injectMultiHopAssociationPMs handle it.
        if (firstJoin.joins().size() >= 2) {
            return null;
        }
        // First-PM-wins is RETAINED (audit 23 probed-and-reverted): a
        // direction-agreement wall broke testSimpleQueryToAssociationMapping
        // + testProjectThroughAssoWithAssociationMapping — classic
        // association mappings legitimately spell the two directions with
        // DIFFERENT (inverse-equivalent) joins, and the first join's
        // predicate is row-correct for both (engine joins are
        // direction-neutral). Residual: two directions with genuinely
        // NON-equivalent joins would still take the first silently.
        // An end class with NO ~mainTable mapping (its properties live only
        // as Join PMs on the other end) cannot anchor a standalone
        // (A,B)->Boolean predicate — no binding is emitted, and NAVIGATING
        // the association stays loud at resolve time ("association not
        // mapped in mapping"). Declaring it is not an error.
        if (anchorTableOf(md, classA, model) == null
                || anchorTableOf(md, classB, model) == null) {
            return null;
        }

        Variable srcRow = new Variable("srcRow");
        Variable tgtRow = new Variable("tgtRow");
        ValueSpecification predicateBody = buildAssocPredicateBody(firstJoin, classA,
                classB, srcRow, tgtRow, am.associationName(), md, model);
        // predicateBody's tgtRow reads the JOIN's landing table; the call
        // declares tgtRow's row type as classB's ~mainTable. Those must be
        // the SAME table or the lambda would silently mistype (checked
        // inside buildAssocPredicateBody, which knows the landing table).

        Variable a = new Variable("a");
        Variable b = new Variable("b");
        // The adapter lambda speaks ROW scope; its row types are knowable
        // right here (the two ends' ~mainTable), so say them: the src/tgt
        // Relation args bind the signature's S,T and the lambda's columns
        // type through the ordinary kernel — no Any punt, no bespoke
        // checker. The resolver reads the tables from the CALL instead of
        // re-deriving them from the classes' mappings.
        ValueSpecification body = new AppliedFunction("legacyAssocPredicate", List.of(
                a, b,
                ViewRelation.sourceRefFor(java.util.Objects.requireNonNull(
                        anchorTableOf(md, classA, model)), model, md),
                ViewRelation.sourceRefFor(java.util.Objects.requireNonNull(
                        anchorTableOf(md, classB, model)), model, md),
                new LambdaFunction(List.of(srcRow, tgtRow),
                                         List.of(predicateBody))));

        FunctionDefinition.ParameterDefinition pA = new FunctionDefinition.ParameterDefinition(
                "a", new TypeExpression.NameRef(classA), Multiplicity.Concrete.PURE_ONE);
        FunctionDefinition.ParameterDefinition pB = new FunctionDefinition.ParameterDefinition(
                "b", new TypeExpression.NameRef(classB), Multiplicity.Concrete.PURE_ONE);
        return new FunctionDefinition(
                SynthFqn.mappingAssoc(md.qualifiedName(), am.associationName()),
                List.of(), List.of(), List.of(pA, pB),
                new TypeExpression.NameRef("meta::pure::metamodel::type::Boolean"),
                Multiplicity.Concrete.PURE_ONE,
                List.of(body),
                List.of(), List.of())
                .withSynthesizedFrom(new FunctionDefinition.Synthesized(
                        SynthHat.ASSOC, md.qualifiedName(), am.associationName()));
    }

    /**
     * Build the predicate body for an AssociationMapping. Single-hop
     * joins translate to the join condition directly over (srcRow,
     * tgtRow). Multi-hop joins chain conditions through intermediate
     * row bindings: each hop's predicate is conjoined with the next
     * via {@code and(...)}, with intermediate rows resolved by named
     * binding through the chain alias scope.
     */
    static ValueSpecification buildAssocPredicateBody(PropertyMapping.Join join,
                                                             String classA, String classB,
                                                             Variable srcRow, Variable tgtRow,
                                                             String associationName,
                                                             LegacyMappingDefinition md,
                                                             ModelBuilder model) {
        if (join.joins().isEmpty()) {
            throw new ModelException(LegendCompileException.Phase.NORMALIZE, 
                    "AssociationMapping for '" + associationName
                  + "' has empty join chain; mapping=" + md.qualifiedName());
        }
        String sourceTable = anchorNameOf(md, classA, model);
        if (join.joins().size() == 1) {
            JoinChainElement hop = join.joins().get(0);
            String hopDb = hop.databaseName() != null ? hop.databaseName() : join.database();
            DatabaseDefinition.JoinDefinition jd = model.findJoin(hopDb, hop.joinName())
                    .orElseThrow(() -> new ModelException(LegendCompileException.Phase.NORMALIZE, 
                            "AssociationMapping join '" + hop.joinName()
                          + "' not found in db '" + hopDb + "'; association='"
                          + associationName + "', mapping=" + md.qualifiedName()));
            // The synthesized legacyAssocPredicate call declares tgtRow's row
            // type as classB's ~mainTable; the join must actually land there,
            // or the lambda's column reads would silently mistype.
            String classBTable = anchorNameOf(md, classB, model);
            RelationalOperation cond2 = MappingNormalizer.resolveViewRefsInJoin(
                    jd.operation(), hopDb, sourceTable, model, md,
                    model.findView(hopDb, sourceTable).isPresent() ? sourceTable : null,
                    null,
                    model.findView(hopDb, classBTable).isPresent() ? classBTable : null,
                    /*anySide*/ true);
            String targetTable = MappingNormalizer.determineTargetTable(cond2, sourceTable,
                    hop.joinName(), associationName, 1, md.qualifiedName());
            if (!targetTable.equals(classBTable)) {
                // a join landing on classB's OWN main source is fine even
                // when that source is a VIEW — the class-source override
                // already expands it, so tgtRow IS the view relation's row
                // (declared column names); any OTHER view target stays a
                // named wall
                MappingNormalizer.requireNonViewTarget(targetTable, hopDb,
                        hop.joinName(), model, md);
            }
            if (!targetTable.equals(classBTable)) {
                throw new NotImplementedException(
                        "AssociationMapping join '" + hop.joinName() + "' lands on table '"
                      + targetTable + "' but the target end class '" + classB
                      + "' is mapped to ~mainTable '" + classBTable + "'; an "
                      + "association joining through a non-mainTable row is not "
                      + "supported. Association='" + associationName + "', mapping="
                      + md.qualifiedName());
            }
            Map<String, ValueSpecification> condScope = new LinkedHashMap<>();
            condScope.put(sourceTable, srcRow);
            if (!targetTable.equals(sourceTable)) condScope.put(targetTable, tgtRow);
            // translate the SAME view-resolved tree the target was picked
            // from — raw refs name pre-resolution tables (T1.10)
            return RelOpTranslator.translate(cond2, condScope, tgtRow, /*rowBind*/ null, RelOpTranslator.PipelineView.NONE);
        }
        // Unreachable: multi-hop associations are intercepted in
        // synthesizeAssociationMapping (returns null) and realized as per-end
        // navigation injected into the class realizing functions (Option A;
        // see docs/MAPPING_LEGACY_TO_FUNCTION.md §5.6.1b). A (A,B)->Boolean
        // predicate cannot bind the intermediate row(s). This guard fires only
        // if that interception is bypassed — a compiler invariant violation.
        throw new ModelException(LegendCompileException.Phase.NORMALIZE, 
                "Multi-hop AssociationMapping for '" + associationName + "' ("
              + join.joins().size() + " join hops) reached the predicate "
              + "builder; it should have been handled by per-end injection. "
              + "Mapping=" + md.qualifiedName());
    }

    /** The predicate ANCHOR table for an association end: the class's own
     * root/sole set's ~mainTable, or — for a class mapped ONLY as an
     * EMBEDDED block — the OWNING set's main table (engine: an embedded
     * set implementation shares its owner's table; Join Firm_Organizations
     * anchors on PERSON_FIRM_DENORM). Null when neither resolves. */
    static LegacyMappingDefinition.@com.legend.Nullable TableReference
            anchorTableOf(LegacyMappingDefinition md, String classFqn,
            ModelBuilder model) {
        if (MappingNormalizer.hasMainTable(md, classFqn, model)) {
            return MappingNormalizer.mainTableDefOf(md, classFqn, model);
        }
        List<LegacyMappingDefinition> closure = new ArrayList<>();
        MappingNormalizer.collectMappingClosure(md, model, closure,
                new HashSet<>());
        for (LegacyMappingDefinition m : closure) {
            for (ClassMapping cm : m.classMappings()) {
                if (!(cm instanceof ClassMapping.Relational rcm)) {
                    continue;
                }
                for (PropertyMapping pm : rcm.propertyMappings()) {
                    if (!(pm instanceof PropertyMapping.Embedded
                            || pm instanceof PropertyMapping
                                    .OtherwiseEmbedded)) {
                        continue;
                    }
                    ClassDefinition owner =
                            model.findClass(rcm.className()).orElse(null);
                    TypeExpression pt = owner == null ? null
                            : MappingNormalizer.findPropertyTypeDeep(owner,
                                    pm.propertyName(), model);
                    if (pt instanceof TypeExpression.NameRef nr
                            && nr.name().equals(classFqn)) {
                        LegacyMappingDefinition.TableReference mt =
                                rcm.mainTable() != null ? rcm.mainTable()
                                        : MappingNormalizer
                                                .inferMainTableQuiet(rcm);
                        if (mt != null) {
                            return mt;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** {@link #anchorTableOf}'s table NAME — loud when unresolvable (the
     * synthesis gate already null-checked). */
    private static String anchorNameOf(LegacyMappingDefinition md,
            String classFqn, ModelBuilder model) {
        return java.util.Objects.requireNonNull(
                anchorTableOf(md, classFqn, model),
                () -> "no predicate anchor for '" + classFqn + "' in "
                        + md.qualifiedName()).table();
    }
}
