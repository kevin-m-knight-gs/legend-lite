// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lineage;

import com.legend.compiler.element.ModelContext;
import com.legend.error.NotImplementedException;
import com.legend.model.AssociationMapping;
import com.legend.model.ClassMapping;
import com.legend.model.DatabaseDefinition;
import com.legend.model.JoinChainElement;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.PropertyMapping;
import com.legend.model.RelationalOperation;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.PackageableElementPtr;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.TypeAnnotation;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The engine's STATIC {@code scanRelations} tree (#44, feature map §14.1:
 * "static form off the mapping" — no pipeline dependency): the tables a
 * query's property demand touches, arranged by JOIN traversal with the
 * MAPPING's join names, each node listing the SORTED columns that table
 * contributes (leaf reads plus its side of every join condition):
 * <pre>
 *   root
 *     ------&gt; (t) Person [ID, name]
 *       ------&gt; (t) Bicycle(PersonBicycle) [b_PersonID]
 * </pre>
 * Union-mapped properties branch per property-mapping entry (the
 * {@code prop[setId]} routing rides {@code Join.targetSetId});
 * {@code subType(@X)} keeps only branches whose target set maps X.
 * Anything outside the built vocabulary is a LOUD wall, never a silently
 * wrong tree.
 */
public final class ScanRelations {

    private ScanRelations() {
    }

    /** One path segment: a property hop or a subType restriction. */
    private sealed interface Seg {
        record Prop(String name) implements Seg { }

        record SubType(String classFqn) implements Seg { }
    }

    private static final class Node {
        final String db;          // defining database (view detection)
        final String table;
        final String joinName;    // null on the root table node
        final Set<String> cols = new TreeSet<>();
        final TreeMap<String, Node> children = new TreeMap<>();

        Node(String db, String table, String joinName) {
            this.db = db;
            this.table = table;
            this.joinName = joinName;
        }
    }

    public static String treeString(ModelContext ctx, LambdaFunction query,
            String mappingFqn) {
        LegacyMappingDefinition md = mapping(ctx, mappingFqn);
        String rootClass = rootClassFqn(query);
        ClassMapping.Relational rootCm = classMappingFor(ctx, md, rootClass,
                null);
        List<List<Seg>> paths = new ArrayList<>();
        collectChains(query, paths);
        Node[] rootTable = new Node[1];
        for (List<Seg> p : paths) {
            if (rootTable[0] == null) {
                rootTable[0] = new Node(mainDbOf(rootCm), mainTableOf(rootCm),
                        null);
            }
            walk(ctx, md, rootCm, rootTable[0], p, 0);
        }
        StringBuilder sb = new StringBuilder("root\n");
        if (rootTable[0] != null) {
            print(sb, rootTable[0], 1, ctx);
        }
        return sb.toString();
    }

    private static void print(StringBuilder sb, Node n, int depth,
            ModelContext ctx) {
        DatabaseDefinition.ViewDefinition vd = n.db == null ? null
                : findView(ctx, n.db, n.table);
        sb.append("  ".repeat(depth)).append("------> (")
                .append(vd != null ? 'v' : 't').append(") ").append(n.table);
        if (n.joinName != null) {
            sb.append('(').append(n.joinName).append(')');
        }
        sb.append(" [").append(String.join(", ", n.cols)).append("]\n");
        for (Node c : n.children.values()) {
            print(sb, c, depth + 1, ctx);
        }
        if (vd != null) {
            // a VIEW EXPANDS: a nested 'root' subtree of its underlying
            // tables — every column mapping expression plus the view
            // filter's join web (the engine's view internals)
            sb.append("  ".repeat(depth + 1)).append("root\n");
            print(sb, expandView(ctx, n.db, vd), depth + 2, ctx);
        }
    }

    /** The view's INTERNAL tree: plain column expressions seed the root
     * table and its columns; JoinNavigation expressions and the view
     * ~filter fold their join chains off it. */
    private static Node expandView(ModelContext ctx, String dbName,
            DatabaseDefinition.ViewDefinition vd) {
        Node root = null;
        List<RelationalOperation.ColumnRef> plainRefs = new ArrayList<>();
        for (DatabaseDefinition.ViewDefinition.ViewColumnMapping cm
                : vd.columnMappings()) {
            if (!(cm.expression()
                    instanceof RelationalOperation.JoinNavigation)) {
                columnRefs(cm.expression(), plainRefs);
            }
        }
        for (RelationalOperation.ColumnRef r : plainRefs) {
            if (root == null) {
                root = new Node(r.databaseName() != null ? r.databaseName()
                        : dbName, bare(r.table()), null);
            }
            if (!bare(r.table()).equals(root.table)) {
                throw new NotImplementedException("scanRelations: view '"
                        + vd.name() + "' columns span tables '" + root.table
                        + "' and '" + r.table() + "'");
            }
            root.cols.add(r.column());
        }
        if (root == null) {
            throw new NotImplementedException("scanRelations: view '"
                    + vd.name() + "' has no plain column to seed its root");
        }
        for (DatabaseDefinition.ViewDefinition.ViewColumnMapping cm
                : vd.columnMappings()) {
            if (cm.expression()
                    instanceof RelationalOperation.JoinNavigation jn) {
                foldJoinNavigation(ctx, root, dbName, jn);
            }
        }
        com.legend.model.FilterMapping fm = vd.filter();
        if (fm instanceof com.legend.model.FilterMapping.JoinMediated jm) {
            Node at = joinChain(ctx, null, root,
                    jm.sourceDb() != null ? jm.sourceDb() : dbName,
                    jm.joins());
            assignFilter(ctx, root, at, jm.sourceDb() != null ? jm.sourceDb()
                    : dbName, jm.filter());
        } else if (fm instanceof com.legend.model.FilterMapping.Direct d) {
            assignFilter(ctx, root, root, dbName, d.filter());
        }
        for (RelationalOperation g : vd.groupByColumns()) {
            List<RelationalOperation.ColumnRef> refs = new ArrayList<>();
            columnRefs(g, refs);
            assignByTable(root, refs);
        }
        return root;
    }

    private static void foldJoinNavigation(ModelContext ctx, Node root,
            String dbName, RelationalOperation.JoinNavigation jn) {
        Node at = joinChain(ctx, null, root,
                jn.databaseName() != null ? jn.databaseName() : dbName,
                jn.chain());
        if (jn.terminal() != null) {
            List<RelationalOperation.ColumnRef> refs = new ArrayList<>();
            columnRefs(jn.terminal(), refs);
            for (RelationalOperation.ColumnRef r : refs) {
                if (bare(r.table()).equals(at.table)) {
                    at.cols.add(r.column());
                } else {
                    assignByTable(root, List.of(r));
                }
            }
        }
    }

    private static void assignFilter(ModelContext ctx, Node root, Node at,
            String dbName, com.legend.model.FilterPointer ptr) {
        String fdb = ptr instanceof com.legend.model.FilterPointer.Cross c
                ? c.db() : dbName;
        DatabaseDefinition db = ctx.findDatabase(fdb).orElseThrow(() ->
                new NotImplementedException("scanRelations: unknown filter"
                        + " database '" + fdb + "'"));
        DatabaseDefinition.FilterDefinition fd = db.filters().stream()
                .filter(f -> f.name().equals(ptr.name())).findFirst()
                .orElseThrow(() -> new NotImplementedException(
                        "scanRelations: unknown filter '" + ptr.name() + "'"));
        List<RelationalOperation.ColumnRef> refs = new ArrayList<>();
        columnRefs(fd.condition(), refs);
        assignByTable(root, refs);
    }

    /** Assign each ref's column to the tree node whose table matches —
     * a ref landing nowhere is loud (the tree would silently lie). */
    private static void assignByTable(Node root,
            List<RelationalOperation.ColumnRef> refs) {
        for (RelationalOperation.ColumnRef r : refs) {
            if (!assignOne(root, r)) {
                throw new NotImplementedException("scanRelations: column '"
                        + r.table() + "." + r.column()
                        + "' matches no tree node");
            }
        }
    }

    private static boolean assignOne(Node n,
            RelationalOperation.ColumnRef r) {
        if (bare(r.table()).equals(n.table)) {
            n.cols.add(r.column());
            return true;
        }
        for (Node c : n.children.values()) {
            if (assignOne(c, r)) {
                return true;
            }
        }
        return false;
    }

    private static DatabaseDefinition.ViewDefinition findView(ModelContext ctx,
            String dbName, String name) {
        DatabaseDefinition db = ctx.findDatabase(dbName).orElse(null);
        if (db == null) {
            return null;
        }
        for (DatabaseDefinition.ViewDefinition v : db.views()) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        for (var sc : db.schemas()) {
            for (DatabaseDefinition.ViewDefinition v : sc.views()) {
                if (v.name().equals(name)) {
                    return v;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The mapping walk
    // ------------------------------------------------------------------

    private static void walk(ModelContext ctx, LegacyMappingDefinition md,
            ClassMapping.Relational cm, Node node, List<Seg> path, int i) {
        if (i >= path.size()) {
            return;
        }
        if (!(path.get(i) instanceof Seg.Prop prop)) {
            throw new NotImplementedException(
                    "scanRelations: subType not following a property hop");
        }
        Seg.SubType st = i + 1 < path.size()
                && path.get(i + 1) instanceof Seg.SubType s ? s : null;
        int next = st == null ? i + 1 : i + 2;
        List<PropertyMapping> pms = pmsFor(ctx, md, cm, prop.name());
        if (pms.isEmpty()) {
            // a SCALAR leaf that is genuinely unmapped is loud; a mid-hop
            // must resolve
            throw new NotImplementedException("scanRelations: property '"
                    + prop.name() + "' has no property mapping in set '"
                    + cm.className() + "'");
        }
        for (PropertyMapping pm : pms) {
            switch (pm) {
                case PropertyMapping.Column c -> node.cols.add(c.column());
                case PropertyMapping.EnumeratedColumn ec ->
                        throw new NotImplementedException(
                                "scanRelations: enum-mapped column leaf");
                case PropertyMapping.Join j -> {
                    ClassMapping.Relational target = targetCm(ctx, md, j,
                            node.table);
                    if (st != null && !typeMatches(target.className(),
                            st.classFqn())) {
                        continue;
                    }
                    Node child = joinChain(ctx, md, node, j.database(),
                            j.joins());
                    walk(ctx, md, target, child, path, next);
                }
                case PropertyMapping.JoinTerminalColumn jt -> {
                    Node child = joinChain(ctx, md, node, jt.database(),
                            jt.joins());
                    if (jt.terminalColumn()
                            instanceof RelationalOperation.ColumnRef cr) {
                        child.cols.add(cr.column());
                    } else {
                        throw new NotImplementedException("scanRelations:"
                                + " non-column join terminal");
                    }
                }
                default -> throw new NotImplementedException("scanRelations: "
                        + pm.getClass().getSimpleName()
                        + " property mapping is not supported yet");
            }
        }
    }

    /** All same-named PMs: class-mapping entries first, association-
     * mapping ends second (matched by source set). */
    private static List<PropertyMapping> pmsFor(ModelContext ctx,
            LegacyMappingDefinition md,
            ClassMapping.Relational cm, String prop) {
        List<PropertyMapping> out = new ArrayList<>();
        for (PropertyMapping pm : cm.propertyMappings()) {
            if (pm.propertyName().equals(prop)) {
                out.add(pm);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        List<AssociationMapping> ams = new ArrayList<>();
        for (LegacyMappingDefinition m : withIncludes(ctx, md)) {
            ams.addAll(allAssociationMappings(m));
        }
        for (AssociationMapping am : ams) {
            for (com.legend.model.AssociationPropertyMapping apm
                    : am.propertyMappings()) {
                if (!apm.propertyName().equals(prop)) {
                    continue;
                }
                if (apm.sourceSetId() != null && cm.setId() != null
                        && !apm.sourceSetId().equals(cm.setId())) {
                    continue;
                }
                out.add(apm.body());
            }
        }
        return out;
    }

    /** Fold a join chain under {@code parent}, assigning each side's
     * condition columns to its node; returns the DEEPEST node. */
    private static Node joinChain(ModelContext ctx, LegacyMappingDefinition md,
            Node parent, String db, List<JoinChainElement> joins) {
        Node cur = parent;
        for (JoinChainElement el : joins) {
            final Node at = cur;
            String dbName = el.databaseName() != null ? el.databaseName() : db;
            DatabaseDefinition.JoinDefinition jd = joinDef(ctx, dbName,
                    el.joinName());
            Set<String> tables = new LinkedHashSet<>();
            List<RelationalOperation.ColumnRef> refs = new ArrayList<>();
            columnRefs(jd.operation(), refs);
            for (RelationalOperation.ColumnRef r : refs) {
                tables.add(bare(r.table()));
            }
            String other = tables.stream()
                    .filter(t -> !t.equals(at.table))
                    .findFirst().orElseThrow(() ->
                            new NotImplementedException("scanRelations:"
                                    + " self-join '" + el.joinName()
                                    + "' is not supported yet"));
            String otherDb = refs.stream()
                    .filter(r -> bare(r.table()).equals(other))
                    .map(RelationalOperation.ColumnRef::databaseName)
                    .filter(Objects::nonNull).findFirst().orElse(dbName);
            Node child = at.children.computeIfAbsent(
                    other + "(" + el.joinName() + ")",
                    k -> new Node(otherDb, other, el.joinName()));
            for (RelationalOperation.ColumnRef r : refs) {
                if (bare(r.table()).equals(at.table)) {
                    at.cols.add(r.column());
                } else if (bare(r.table()).equals(child.table)) {
                    child.cols.add(r.column());
                } else {
                    throw new NotImplementedException("scanRelations: join '"
                            + el.joinName() + "' touches a third table '"
                            + r.table() + "'");
                }
            }
            cur = child;
        }
        return cur;
    }

    private static void columnRefs(RelationalOperation op,
            List<RelationalOperation.ColumnRef> out) {
        switch (op) {
            case RelationalOperation.ColumnRef cr -> out.add(cr);
            case RelationalOperation.Comparison c -> {
                columnRefs(c.left(), out);
                columnRefs(c.right(), out);
            }
            case RelationalOperation.BooleanOp b -> {
                columnRefs(b.left(), out);
                columnRefs(b.right(), out);
            }
            case RelationalOperation.Group g -> columnRefs(g.inner(), out);
            case RelationalOperation.IsNull n -> columnRefs(n.operand(), out);
            case RelationalOperation.IsNotNull n ->
                    columnRefs(n.operand(), out);
            case RelationalOperation.FunctionCall f -> {
                for (RelationalOperation a : f.args()) {
                    columnRefs(a, out);
                }
            }
            case RelationalOperation.Literal ignored -> { }
            default -> throw new NotImplementedException("scanRelations:"
                    + " join condition node "
                    + op.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    private static LegacyMappingDefinition mapping(ModelContext ctx,
            String fqn) {
        return ctx.findLegacyMapping(fqn).orElseThrow(() ->
                new NotImplementedException("scanRelations: unknown legacy"
                        + " mapping '" + fqn + "'"));
    }

    private static List<ClassMapping.Relational> allClassMappings(
            LegacyMappingDefinition md) {
        List<ClassMapping.Relational> out = new ArrayList<>();
        for (ClassMapping cm : md.classMappings()) {
            if (cm instanceof ClassMapping.Relational r) {
                out.add(r);
            }
        }
        return out;
    }

    private static List<AssociationMapping> allAssociationMappings(
            LegacyMappingDefinition md) {
        return md.associationMappings();
    }

    /** {@code md} plus its include closure, own-first (own definitions
     * shadow included ones, the mapping-include rule). */
    private static List<LegacyMappingDefinition> withIncludes(ModelContext ctx,
            LegacyMappingDefinition md) {
        List<LegacyMappingDefinition> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        collectIncludes(ctx, md, out, seen);
        return out;
    }

    private static void collectIncludes(ModelContext ctx,
            LegacyMappingDefinition md, List<LegacyMappingDefinition> out,
            Set<String> seen) {
        if (!seen.add(md.qualifiedName())) {
            return;
        }
        out.add(md);
        for (com.legend.model.MappingInclude inc : md.includes()) {
            ctx.findLegacyMapping(inc.mappingPath()).ifPresent(in ->
                    collectIncludes(ctx, in, out, seen));
        }
    }

    private static ClassMapping.Relational classMappingFor(ModelContext ctx,
            LegacyMappingDefinition md, String classFqn, String setId) {
        List<ClassMapping.Relational> hits = new ArrayList<>();
        for (LegacyMappingDefinition m : withIncludes(ctx, md)) {
            for (ClassMapping.Relational r : allClassMappings(m)) {
                if (setId != null ? setId.equals(r.setId())
                        : typeMatches(r.className(), classFqn)) {
                    hits.add(r);
                }
            }
        }
        if (hits.size() != 1) {
            throw new NotImplementedException("scanRelations: "
                    + hits.size() + " class mappings for '"
                    + (setId != null ? "[" + setId + "]" : classFqn) + "'");
        }
        return hits.get(0);
    }

    /** The join PM's target class mapping: explicit {@code targetSetId}
     * wins; else the single set whose main table is the join's landing
     * table. */
    private static ClassMapping.Relational targetCm(ModelContext ctx,
            LegacyMappingDefinition md, PropertyMapping.Join j,
            String fromTable) {
        if (j.targetSetId() != null) {
            return classMappingFor(ctx, md, null, j.targetSetId());
        }
        JoinChainElement last = j.joins().get(j.joins().size() - 1);
        DatabaseDefinition.JoinDefinition jd = joinDef(ctx,
                last.databaseName() != null ? last.databaseName()
                        : j.database(), last.joinName());
        List<RelationalOperation.ColumnRef> refs = new ArrayList<>();
        columnRefs(jd.operation(), refs);
        List<ClassMapping.Relational> hits = new ArrayList<>();
        List<ClassMapping.Relational> all = new ArrayList<>();
        for (LegacyMappingDefinition m : withIncludes(ctx, md)) {
            all.addAll(allClassMappings(m));
        }
        for (RelationalOperation.ColumnRef r : refs) {
            for (ClassMapping.Relational cm : all) {
                String mt;
                try {
                    mt = mainTableOf(cm);
                } catch (NotImplementedException e) {
                    continue;
                }
                if (mt.equals(bare(r.table())) && !hits.contains(cm)) {
                    hits.add(cm);
                }
            }
        }
        hits.removeIf(cm -> mainTableOf(cm).equals(bare(fromTable)));
        if (hits.size() != 1) {
            throw new NotImplementedException("scanRelations: join target of"
                    + " '" + j.propertyName() + "' is ambiguous ("
                    + hits.size() + " sets)");
        }
        return hits.get(0);
    }

    private static DatabaseDefinition.JoinDefinition joinDef(ModelContext ctx,
            String dbName, String joinName) {
        DatabaseDefinition db = ctx.findDatabase(dbName).orElseThrow(() ->
                new NotImplementedException("scanRelations: unknown database '"
                        + dbName + "'"));
        return db.joins().stream()
                .filter(j -> j.name().equals(joinName)).findFirst()
                .orElseThrow(() -> new NotImplementedException(
                        "scanRelations: unknown join '" + joinName + "'"));
    }

    /** As-written vs resolved class spellings: exact first, then an
     * unambiguous tail match (mapping models keep source spellings). */
    private static boolean typeMatches(String written, String fqn) {
        if (Objects.equals(written, fqn)) {
            return true;
        }
        String wt = written.contains("::")
                ? written.substring(written.lastIndexOf("::") + 2) : written;
        String ft = fqn.contains("::")
                ? fqn.substring(fqn.lastIndexOf("::") + 2) : fqn;
        return wt.equals(ft)
                && (!written.contains("::") || !fqn.contains("::"));
    }

    /** The set's main table — explicit {@code ~mainTable}, else IMPLIED
     * by the first column property mapping (engine grammar: mainTable is
     * 0..1). */
    private static String mainTableOf(ClassMapping.Relational cm) {
        if (cm.mainTable() != null) {
            return bare(cm.mainTable().table());
        }
        for (PropertyMapping pm : cm.propertyMappings()) {
            if (pm instanceof PropertyMapping.Column c) {
                return bare(c.table());
            }
        }
        throw new NotImplementedException("scanRelations: set '"
                + cm.className() + "' has no main table (explicit or"
                + " implied by a column mapping)");
    }

    private static String mainDbOf(ClassMapping.Relational cm) {
        if (cm.mainTable() != null) {
            return cm.mainTable().database();
        }
        for (PropertyMapping pm : cm.propertyMappings()) {
            if (pm instanceof PropertyMapping.Column c) {
                return c.database();
            }
        }
        return null;
    }

    private static String bare(String table) {
        return table != null && table.contains(".")
                ? table.substring(table.lastIndexOf('.') + 1) : table;
    }

    // ------------------------------------------------------------------
    // Query-side extraction
    // ------------------------------------------------------------------

    private static String rootClassFqn(ValueSpecification n) {
        if (n instanceof AppliedFunction af) {
            if ("getAll".equals(af.function()) && !af.parameters().isEmpty()
                    && af.parameters().get(0)
                            instanceof PackageableElementPtr p) {
                return p.fullPath();
            }
            for (ValueSpecification c : af.parameters()) {
                try {
                    return rootClassFqn(c);
                } catch (IllegalStateException ignore) {
                    // keep scanning
                }
            }
        }
        if (n instanceof LambdaFunction lf) {
            for (ValueSpecification b : lf.body()) {
                try {
                    return rootClassFqn(b);
                } catch (IllegalStateException ignore) {
                    // keep scanning
                }
            }
        }
        if (n instanceof AppliedProperty ap) {
            return rootClassFqn(ap.receiver());
        }
        throw new IllegalStateException("no getAll root");
    }

    /** Every var-rooted property chain in the query (any lambda), with
     * subType restrictions kept as segments. */
    private static void collectChains(ValueSpecification n,
            List<List<Seg>> out) {
        List<Seg> chain = chainOf(n);
        if (chain != null) {
            if (!chain.isEmpty()) {
                out.add(chain);
            }
            return;
        }
        switch (n) {
            case AppliedFunction af -> af.parameters()
                    .forEach(p -> collectChains(p, out));
            case AppliedProperty ap -> collectChains(ap.receiver(), out);
            case LambdaFunction lf -> lf.body()
                    .forEach(b -> collectChains(b, out));
            case PureCollection pc -> pc.values()
                    .forEach(v -> collectChains(v, out));
            default -> { }
        }
    }

    /** The segment list when {@code n} IS a var-rooted chain (possibly
     * with subType/toOne links); null when it is not a chain. */
    private static List<Seg> chainOf(ValueSpecification n) {
        if (n instanceof Variable) {
            return new ArrayList<>();
        }
        if (n instanceof AppliedProperty ap) {
            List<Seg> base = chainOf(ap.receiver());
            if (base == null) {
                return null;
            }
            base.add(new Seg.Prop(ap.property()));
            return base;
        }
        if (n instanceof AppliedFunction af
                && "subType".equals(simple(af.function()))
                && af.parameters().size() == 2) {
            List<Seg> base = chainOf(af.parameters().get(0));
            if (base == null) {
                return null;
            }
            base.add(new Seg.SubType(typeName(af.parameters().get(1))));
            return base;
        }
        if (n instanceof AppliedFunction af && af.parameters().size() == 1) {
            // single-arg calls over a chain (toOne/isEmpty/count/...):
            // transparent CONSUMERS — the chain itself is the demand
            return chainOf(af.parameters().get(0));
        }
        if (n instanceof AppliedFunction af && af.parameters().size() >= 2
                && af.parameters().stream().skip(1)
                        .noneMatch(a -> a instanceof LambdaFunction)) {
            // a call with non-lambda extras over a chain is a QUALIFIED
            // PROPERTY hop (milestoned dates: product($businessDate)) —
            // a non-property lands on the walk's loud unmapped wall,
            // never a silently wrong tree
            List<Seg> base = chainOf(af.parameters().get(0));
            if (base == null) {
                return null;
            }
            base.add(new Seg.Prop(simple(af.function())));
            return base;
        }
        return null;
    }

    private static String typeName(ValueSpecification v) {
        if (v instanceof TypeAnnotation.Named named) {
            return switch (named.type()) {
                case com.legend.model.TypeExpression.NameRef nr -> nr.name();
                case com.legend.model.TypeExpression.Generic g -> g.name();
                default -> throw new NotImplementedException(
                        "scanRelations: structural subType annotation");
            };
        }
        if (v instanceof PackageableElementPtr p) {
            return p.fullPath();
        }
        throw new NotImplementedException(
                "scanRelations: subType argument "
                + v.getClass().getSimpleName());
    }

    private static String simple(String f) {
        return f.substring(f.lastIndexOf(':') + 1);
    }

    static Optional<ClassMapping.Relational> rootFor(ModelContext ctx,
            LegacyMappingDefinition md, String classFqn) {
        try {
            return Optional.of(classMappingFor(ctx, md, classFqn, null));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
