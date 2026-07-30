// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.compiler.SynthFqn;
import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;
import com.legend.error.NotImplementedException;
import com.legend.model.AssociationDefinition;
import com.legend.model.AssociationMapping;
import com.legend.model.ClassMapping;
import com.legend.model.FunctionDefinition;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.Multiplicity;
import com.legend.model.PropertyMapping;
import com.legend.model.SynthHat;
import com.legend.model.TypeExpression;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.CString;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XStore association ENDS and the PROPERTY-SPACE emission (route A,
 * docs/XSTORE_LEG.md): a {@code ClassMapping.Pure} end has no relation at
 * normalize time — its rows exist only once the resolver composes the M2M
 * set over its (possibly JSON-framed) source. The synthesized predicate
 * for such an association therefore pins the two SETS by id and keeps the
 * condition in PROPERTY space over the end classes:
 * {@code legacyAssocPredicate(a, b, 'setA', 'setB', {srcRow,tgtRow|cond})}
 * (the String-args overload). Set-LOCAL ({@code +prop}) reads cannot type
 * as class properties, so they emit as
 * {@code legacyLocalProperty($row, 'prop')} — conform-by-emission; the
 * resolver's association route substitutes both sides through the sets'
 * composed bindings. Relational/Relation ends keep the column-space
 * emission ({@link MappingNormalizer#synthesizeXStoreMapping}) verbatim.
 */
final class XStorePureEnds {

    private XStorePureEnds() {
    }

    /** An XStore END resolved against the mapping: a Relation(~func) set
     * (pipeline + column view), a RELATIONAL set converted to its column
     * view, or a PURE set (property space — no pipeline, no column view;
     * {@code localProps} names its {@code +prop} lines). */
    record XEnd(@com.legend.Nullable ValueSpecification pipeline, 
            ClassMapping.@com.legend.Nullable RelationFunction colsView, 
            String setId, boolean pure, Set<String> localProps) {
    }

    static XEnd xstoreEndOf(LegacyMappingDefinition md,
            String classFqn, @com.legend.Nullable String setId, ModelBuilder model) {
        // the end sets may live in INCLUDED mappings (modelJoins:
        // XStore lines over include LegalEntityMapping/TradesMapping) —
        // the engine compiles the include closure as one mapping
        List<LegacyMappingDefinition> closure = new ArrayList<>();
        MappingNormalizer.collectMappingClosure(md, model, closure,
                new LinkedHashSet<>());
        List<ClassMapping> cms = new ArrayList<>();
        for (LegacyMappingDefinition m : closure) {
            cms.addAll(m.classMappings());
        }
        for (ClassMapping cm : cms) {
            if (cm instanceof ClassMapping.RelationFunction rf
                    && rf.className().equals(classFqn)
                    && (setId == null
                            || setId.equals(MappingNormalizer.setIdOf(rf)))) {
                Set<String> locals = new LinkedHashSet<>();
                for (ClassMapping.RelationFunction.Col c : rf.columns()) {
                    if (c.local()) {
                        locals.add(c.property());
                    }
                }
                return new XEnd(
                        MappingNormalizer.relationFunctionPipeline(rf, model),
                        rf, MappingNormalizer.setIdOf(rf), false, locals);
            }
        }
        for (ClassMapping cm : cms) {
            if (cm instanceof ClassMapping.Relational rcm
                    && rcm.className().equals(classFqn)
                    && (setId == null
                            || setId.equals(MappingNormalizer.setIdOf(rcm)))) {
                List<ClassMapping.RelationFunction.Col> cols = new ArrayList<>();
                Set<String> locals = new LinkedHashSet<>();
                for (PropertyMapping pm : rcm.propertyMappings()) {
                    if (pm instanceof PropertyMapping.Column c) {
                        cols.add(new ClassMapping.RelationFunction.Col(
                                c.propertyName(), c.column(), false));
                    } else if (pm instanceof PropertyMapping.LocalProperty lp) {
                        if (lp.body() instanceof PropertyMapping.Column lc) {
                            cols.add(new ClassMapping.RelationFunction.Col(
                                    lp.propertyName(), lc.column(), true));
                        }
                        // ALL locals mark (expression-bodied +props too:
                        // toString([db]col) — the property-space route
                        // reads them through the set's composed bindings)
                        locals.add(lp.propertyName());
                    }
                }
                return new XEnd(
                        ViewRelation.mainSourceRef(md, classFqn, model),
                        new ClassMapping.RelationFunction(classFqn,
                                MappingNormalizer.setIdOf(rcm), null, rcm.root(),
                                "<relational>", cols),
                        MappingNormalizer.setIdOf(rcm), false, locals);
            }
        }
        for (ClassMapping cm : cms) {
            if (cm instanceof ClassMapping.Pure pcm
                    && pcm.className().equals(classFqn)
                    && (setId == null
                            || setId.equals(MappingNormalizer.setIdOf(pcm)))) {
                Set<String> locals = new LinkedHashSet<>();
                for (ClassMapping.Pure.PropertyBinding pb
                        : pcm.propertyBindings()) {
                    if (pb.local()) {
                        locals.add(pb.propertyName());
                    }
                }
                return new XEnd(null, null,
                        MappingNormalizer.setIdOf(pcm), true, locals);
            }
        }
        throw new NotImplementedException(
                "XStore/ModelJoin association end class '" + classFqn
                + "' resolves to no Relation or Relational set"
                + (setId != null ? " for set id '" + setId + "'" : "")
                + " in '" + md.qualifiedName() + "'");
    }

    /**
     * The property-space synthesis — same orientation and
     * direction-agreement rules as the column-space emission, condition
     * kept over the end classes with set-local reads marked.
     */
    static FunctionDefinition synthesize(LegacyMappingDefinition md,
            AssociationMapping.Cross xs, AssociationDefinition ad,
            String classA, String classB, XEnd endA, XEnd endB) {
        Variable srcRow = new Variable("srcRow");
        Variable tgtRow = new Variable("tgtRow");
        boolean selfAssoc = classA.equals(classB);
        List<ValueSpecification> conds = new ArrayList<>();
        for (AssociationMapping.Cross.XStoreProperty cand
                : xs.propertyMappings2()) {
            boolean isProp1 = cand.propertyName()
                    .equals(ad.property1().propertyName());
            if (!isProp1 && !cand.propertyName()
                    .equals(ad.property2().propertyName())) {
                throw new ModelException(
                        LegendCompileException.Phase.NORMALIZE,
                        "XStore line '" + cand.propertyName() + "' matches"
                        + " neither end of association '" + xs.associationName()
                        + "'; mapping=" + md.qualifiedName());
            }
            // orientation mirrors synthesizeXStoreMapping (audit 8 S1)
            Variable thatRow;
            if (selfAssoc) {
                thatRow = isProp1 ? tgtRow : srcRow;
            } else {
                thatRow = isProp1 ? srcRow : tgtRow;
            }
            Variable thisRow = thatRow == srcRow ? tgtRow : srcRow;
            XEnd thatEnd = isProp1 ? endA : endB;
            XEnd thisEnd = isProp1 ? endB : endA;
            conds.add(MappingNormalizer.canonicalizeEqualOperands(
                    renameReads(cand.expression(),
                            Map.of("this", thisRow, "that", thatRow),
                            Map.of("this", thisEnd, "that", thatEnd)),
                    srcRow.name()));
        }
        if (conds.isEmpty()) {
            throw new ModelException(
                    LegendCompileException.Phase.NORMALIZE,
                    "XStore mapping for '" + xs.associationName()
                    + "' has no property lines; mapping=" + md.qualifiedName());
        }
        ValueSpecification cond = conds.get(0);
        for (ValueSpecification c : conds) {
            if (!c.equals(cond)) {
                throw new NotImplementedException(
                        "XStore association '" + xs.associationName()
                        + "' has direction-specific conditions; a single"
                        + " shared predicate is required for now (mapping="
                        + md.qualifiedName() + ")");
            }
        }
        Variable a = new Variable("a");
        Variable b = new Variable("b");
        ValueSpecification body = new AppliedFunction("legacyAssocPredicate",
                List.of(a, b,
                        new CString(endA.setId()),
                        new CString(endB.setId()),
                        new LambdaFunction(List.of(srcRow, tgtRow),
                                List.of(cond))));
        FunctionDefinition.ParameterDefinition pA =
                new FunctionDefinition.ParameterDefinition("a",
                        new TypeExpression.NameRef(classA),
                        Multiplicity.Concrete.PURE_ONE);
        FunctionDefinition.ParameterDefinition pB =
                new FunctionDefinition.ParameterDefinition("b",
                        new TypeExpression.NameRef(classB),
                        Multiplicity.Concrete.PURE_ONE);
        return new FunctionDefinition(
                SynthFqn.mappingAssoc(md.qualifiedName(), xs.associationName()),
                List.of(), List.of(), List.of(pA, pB),
                new TypeExpression.NameRef(
                        "meta::pure::metamodel::type::Boolean"),
                Multiplicity.Concrete.PURE_ONE,
                List.of(body),
                List.of(), List.of())
                .withSynthesizedFrom(new FunctionDefinition.Synthesized(
                        SynthHat.ASSOC, md.qualifiedName(),
                        xs.associationName()));
    }

    /** {@code $this.p}/{@code $that.p} → the oriented row var's read —
     * a plain property read, or the {@code legacyLocalProperty} marker
     * when the end SET declares {@code p} as a local ({@code +p}). Deep
     * reads recurse through the receiver (the head var renames; the tail
     * types — or walls — at compile). */
    private static ValueSpecification renameReads(ValueSpecification v,
            Map<String, Variable> rowByVar, Map<String, XEnd> endByVar) {
        if (v instanceof AppliedProperty ap
                && ap.receiver() instanceof Variable var
                && rowByVar.containsKey(var.name())) {
            Variable row = rowByVar.get(var.name());
            if (java.util.Objects.requireNonNull(endByVar.get(var.name())).localProps().contains(ap.property())) {
                return new AppliedFunction("legacyLocalProperty",
                        List.of(row, new CString(ap.property())));
            }
            return new AppliedProperty(row, ap.property());
        }
        return switch (v) {
            case Variable var when rowByVar.containsKey(var.name()) ->
                    rowByVar.get(var.name());
            case AppliedFunction af -> new AppliedFunction(af.function(),
                    af.parameters().stream().map(x ->
                            renameReads(x, rowByVar, endByVar)).toList());
            case AppliedProperty ap2 -> new AppliedProperty(
                    renameReads(ap2.receiver(), rowByVar, endByVar),
                    ap2.property());
            case PureCollection pc -> new PureCollection(
                    pc.values().stream().map(x ->
                            renameReads(x, rowByVar, endByVar)).toList());
            case LambdaFunction lf -> new LambdaFunction(lf.parameters(),
                    lf.body().stream().map(x ->
                            renameReads(x, rowByVar, endByVar)).toList());
            default -> v;
        };
    }
}
