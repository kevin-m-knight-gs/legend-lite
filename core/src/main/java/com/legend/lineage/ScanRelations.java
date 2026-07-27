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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        // SYNTHETIC edge condition (tableToTDS ->join lambdas — no named
        // store join exists); null on model-join edges
        RelationalOperation cond;
        // a BARE tableToTDS side (no project): the whole table is the
        // demand — string retention must not narrow it
        boolean keepAll;

        Node(String db, String table, String joinName) {
            this.db = db;
            this.table = table;
            this.joinName = joinName;
        }
    }

    public static String treeString(ModelContext ctx, LambdaFunction query,
            String mappingFqn) {
        StringBuilder sb = new StringBuilder("root\n");
        for (Node r : buildRoots(ctx, query, mappingFqn)) {
            print(sb, r, 1, ctx);
        }
        return sb.toString();
    }

    /**
     * The scanned relation tree as DATA (the #46 test-data-generation
     * consumer): the same walk {@link #treeString} renders, one node per
     * reached table with the join edge that reached it and the scanned
     * columns. Views are NOT expanded here (treeString expands them at
     * print time); the consumer sees the view node itself.
     */
    public record Rel(String db, String table, String joinName,
            RelationalOperation cond, List<String> cols,
            List<Rel> children) {

        /** Model-join edge (no synthetic condition). */
        public Rel(String db, String table, String joinName,
                List<String> cols, List<Rel> children) {
            this(db, table, joinName, null, cols, children);
        }
    }

    public static List<Rel> relTree(ModelContext ctx, LambdaFunction query,
            String mappingFqn) {
        // tableToTDS(tableReference(db,s,t)) roots: the relation IS the
        // table, every column in play (engine scans TDS queries through
        // the plan; the direct-table shape needs no mapping walk). Gated
        // to the DATA consumer — treeString's lineage goldens keep their
        // current vocabulary.
        List<Node> tdsRoots = tableToTdsRoots(ctx, query);
        List<Rel> out = new ArrayList<>();
        if (!tdsRoots.isEmpty()) {
            for (Node r : tdsRoots) {
                out.add(toRel(r));
            }
            return out;
        }
        List<Node> roots = buildRoots(ctx, query, mappingFqn, true);
        if (roots.isEmpty()) {
            // constant-only projection: no scanned path, but the root
            // class's table is still the tree (engine testConstant)
            LegacyMappingDefinition md = mapping(ctx, mappingFqn);
            for (ClassMapping.Relational cm : rootClassMappings(ctx, md,
                    rootClassFqn(query))) {
                Node r = new Node(mainDbOf(cm), mainTableOf(cm), null);
                foldClassFilter(ctx, r, cm);
                roots.add(r);
            }
        }
        for (Node r : roots) {
            out.add(toRel(r));
        }
        return out;
    }

    /** Every {@code tableToTDS(tableReference(db, 'schema', 'table'))}
     * root in the query, document order; join steps are a named wall.
     * Column demand: TDS ops name columns as STRINGS (getString('COL'),
     * groupBy(['COL'])) — literals matching a table column restrict its
     * fetch set (PK rides via the consumer); no matching literal = the
     * bare-tableToTDS whole-table shape. */
    private static List<Node> tableToTdsRoots(ModelContext ctx,
            ValueSpecification n) {
        List<Node> out = new ArrayList<>();
        if (containsCall(n, "join")) {
            // ->join(tableToTDS(...), TYPE, {a,b|...}) chains: each right
            // side becomes a CHILD of the node owning its left-side
            // column, with the lambda as a SYNTHETIC edge condition
            // (aliases resolve through the project cols)
            ValueSpecification top = n instanceof LambdaFunction lf
                    && !lf.body().isEmpty()
                    ? lf.body().get(lf.body().size() - 1) : n;
            // peel post-join wrapper ops (olapGroupBy, sort, ...) down to
            // the join spine
            while (top instanceof AppliedFunction w
                    && !w.function()
                            .substring(w.function().lastIndexOf(':') + 1)
                            .equals("join")
                    && !w.parameters().isEmpty()
                    && containsCall(w.parameters().get(0), "join")) {
                top = w.parameters().get(0);
            }
            Map<String, String[]> aliases = new LinkedHashMap<>();
            Map<String, Node> byTable = new LinkedHashMap<>();
            parseTdsJoinChain(ctx, top, out, aliases, byTable);
            // chain nodes narrowed per-source in parseTdsSource (the
            // global string pool cross-matches other sources' aliases)
            return out;
        }
        collectTableToTds(ctx, n, out);
        if (out.isEmpty()) {
            return out;
        }
        Set<String> strings = new LinkedHashSet<>();
        collectStrings(n, strings);
        for (Node node : walkAll(out)) {
            Set<String> demanded = new TreeSet<>();
            for (String s : strings) {
                for (String c : node.cols) {
                    if (c.equalsIgnoreCase(s)) {
                        demanded.add(c);
                    }
                }
            }
            if (!demanded.isEmpty()) {
                node.cols.retainAll(demanded);
            }
        }
        return out;
    }

    private static List<Node> walkAll(List<Node> roots) {
        List<Node> all = new ArrayList<>();
        java.util.ArrayDeque<Node> work = new java.util.ArrayDeque<>(roots);
        while (!work.isEmpty()) {
            Node x = work.poll();
            all.add(x);
            work.addAll(x.children.values());
        }
        return all;
    }

    /** Recursive descent over {@code join(left, right, TYPE, lambda)}
     * spines; the base tds source becomes the root. Alias map entries are
     * {@code alias -> [table, physicalColumn]}. */
    private static void parseTdsJoinChain(ModelContext ctx,
            ValueSpecification v, List<Node> roots,
            Map<String, String[]> aliases, Map<String, Node> byTable) {
        if (v instanceof AppliedFunction af && af.function()
                .substring(af.function().lastIndexOf(':') + 1)
                .equals("join") && af.parameters().size() >= 3) {
            parseTdsJoinChain(ctx, af.parameters().get(0), roots, aliases,
                    byTable);
            TdsSrc right = parseTdsSource(ctx, af.parameters().get(1),
                    aliases, byTable);
            if (!(lastParam(af) instanceof LambdaFunction cl)
                    || cl.parameters().size() != 2 || cl.body().isEmpty()) {
                throw new NotImplementedException("scanRelations:"
                        + " tableToTDS join without a 2-param condition"
                        + " lambda");
            }
            attachTdsJoin(cl, right, aliases, byTable);
            return;
        }
        TdsSrc base = parseTdsSource(ctx, v, aliases, byTable);
        roots.add(base.node());
    }

    /** A parsed tds source with ITS OWN alias map (alias ->
     * [table, physicalColumn]). */
    private record TdsSrc(Node node, Map<String, String[]> own) {
    }

    private static ValueSpecification lastParam(AppliedFunction af) {
        return af.parameters().get(af.parameters().size() - 1);
    }

    /** {@code $a.getX('L') == $b.getX('R')}: resolve L through the
     * accumulated alias map to (ownerTable, physCol); the owner's node
     * gains the child. The RIGHT side rides as {@code {target}} so the
     * renderer aliases it even on self-joins. */
    private static void attachTdsJoin(LambdaFunction cl, TdsSrc rightSrc,
            Map<String, String[]> aliases, Map<String, Node> byTable) {
        Node right = rightSrc.node();
        ValueSpecification body = cl.body().get(cl.body().size() - 1);
        String leftVar = cl.parameters().get(0).name();
        if (!(body instanceof AppliedFunction eq
                && eq.function().substring(eq.function().lastIndexOf(':') + 1)
                        .equals("equal")
                && eq.parameters().size() == 2)) {
            throw new NotImplementedException("scanRelations: tableToTDS"
                    + " join condition beyond a single equality pending");
        }
        String[] l = null;
        String r = null;
        for (ValueSpecification side : eq.parameters()) {
            String[] read = tdsColRead(side);
            if (read == null) {
                throw new NotImplementedException("scanRelations:"
                        + " tableToTDS join side is not a column read");
            }
            if (read[0].equals(leftVar)) {
                l = aliases.get(read[1]);
                if (l == null) {
                    throw new NotImplementedException("scanRelations:"
                            + " tableToTDS join alias '" + read[1]
                            + "' is not a projected column");
                }
            } else {
                // the right side reads ITS OWN projected alias
                String[] own = rightSrc.own().get(read[1]);
                r = own != null ? own[1] : read[1];
            }
        }
        if (l == null || r == null) {
            throw new NotImplementedException("scanRelations: tableToTDS"
                    + " join condition must compare left and right sides");
        }
        Node parent = byTable.get(l[0]);
        parent.cols.add(l[1]);
        right.cols.add(r);
        right.cond = new RelationalOperation.Comparison(
                new RelationalOperation.ColumnRef(null, l[0], l[1]),
                com.legend.model.ComparisonOp.EQ,
                new RelationalOperation.TargetColumnRef(r));
        parent.children.put(right.table + "(tds_join_"
                + parent.children.size() + ")", right);
    }

    /** {@code $v.getX('COL')} -> [varName, COL]. */
    private static String[] tdsColRead(ValueSpecification v) {
        if (v instanceof AppliedFunction af
                && af.function().substring(af.function().lastIndexOf(':') + 1)
                        .startsWith("get")
                && af.parameters().size() == 2
                && af.parameters().get(0)
                        instanceof com.legend.model.spec.Variable var
                && af.parameters().get(1)
                        instanceof com.legend.model.spec.CString c) {
            return new String[]{var.name(), c.value()};
        }
        return null;
    }

    /** A tds source: {@code tableToTDS(tableReference(...))} optionally
     * under {@code ->project([col(r|$r.getX('PHYS'), 'alias'), ...])};
     * merges its alias map (or the identity map) into {@code aliases}. */
    private static TdsSrc parseTdsSource(ModelContext ctx,
            ValueSpecification v, Map<String, String[]> aliases,
            Map<String, Node> byTable) {
        List<AppliedFunction> projects = new ArrayList<>();
        ValueSpecification cur = v;
        while (cur instanceof AppliedFunction af && af.function()
                .substring(af.function().lastIndexOf(':') + 1)
                .equals("project")) {
            projects.add(af);
            cur = af.parameters().get(0);
        }
        List<Node> found = new ArrayList<>();
        collectTableToTds(ctx, cur, found);
        if (found.size() != 1) {
            throw new NotImplementedException("scanRelations: tableToTDS"
                    + " join side is not a single table source");
        }
        Node node = found.get(0);
        // LEFT-owner registry: a same-table RIGHT side must not shadow
        // the accumulated left (the self-join test's parent lookup)
        byTable.putIfAbsent(node.table, node);
        Map<String, String[]> own = new LinkedHashMap<>();
        if (projects.isEmpty()) {
            node.keepAll = true;
            for (String c : node.cols) {
                own.put(c, new String[]{node.table, c});
            }
        } else {
            for (AppliedFunction p : projects) {
                for (ValueSpecification e
                        : flatValues(p.parameters().get(1))) {
                    if (e instanceof AppliedFunction colFn
                            && colFn.function().substring(
                                    colFn.function().lastIndexOf(':') + 1)
                                    .equals("col")
                            && colFn.parameters().size() >= 2
                            && colFn.parameters().get(1)
                                    instanceof com.legend.model.spec.CString a
                            && colFn.parameters().get(0)
                                    instanceof LambdaFunction cl
                            && !cl.body().isEmpty()) {
                        String[] read = tdsColRead(
                                cl.body().get(cl.body().size() - 1));
                        if (read != null) {
                            own.put(a.value(),
                                    new String[]{node.table, read[1]});
                        }
                    }
                }
            }
        }
        if (!node.keepAll) {
            // demand = this source's OWN projected physical columns
            Set<String> phys = new TreeSet<>();
            for (String[] t : own.values()) {
                if (t[0].equals(node.table)) {
                    phys.add(t[1]);
                }
            }
            node.cols.retainAll(phys);
        }
        // accumulated view: LEFT-most binding wins duplicate names
        own.forEach(aliases::putIfAbsent);
        return new TdsSrc(node, own);
    }

    private static List<ValueSpecification> flatValues(ValueSpecification v) {
        if (v instanceof com.legend.model.spec.PureCollection pc) {
            List<ValueSpecification> out = new ArrayList<>();
            for (ValueSpecification e : pc.values()) {
                out.addAll(flatValues(e));
            }
            return out;
        }
        return List.of(v);
    }

    private static void collectStrings(ValueSpecification n,
            Set<String> out) {
        if (n instanceof com.legend.model.spec.CString cs) {
            out.add(cs.value());
        } else if (n instanceof AppliedFunction af) {
            for (ValueSpecification p : af.parameters()) {
                collectStrings(p, out);
            }
        } else if (n instanceof AppliedProperty ap) {
            collectStrings(ap.receiver(), out);
        } else if (n instanceof LambdaFunction lf) {
            for (ValueSpecification b : lf.body()) {
                collectStrings(b, out);
            }
        } else if (n instanceof com.legend.model.spec.PureCollection pc) {
            for (ValueSpecification e : pc.values()) {
                collectStrings(e, out);
            }
        }
    }

    private static void collectTableToTds(ModelContext ctx,
            ValueSpecification n, List<Node> out) {
        if (n instanceof AppliedFunction af) {
            String simple = af.function()
                    .substring(af.function().lastIndexOf(':') + 1);
            if ("tableToTDS".equals(simple) && !af.parameters().isEmpty()
                    && af.parameters().get(0) instanceof AppliedFunction tr
                    && tr.function().endsWith("tableReference")
                    && tr.parameters().size() >= 3
                    && tr.parameters().get(0)
                            instanceof PackageableElementPtr db
                    && tr.parameters().get(2)
                            instanceof com.legend.model.spec.CString t) {
                Node node = new Node(db.fullPath(), t.value(), null);
                var td = ctx.findTableDefinition(db.fullPath(), t.value());
                if (td.isPresent()) {
                    for (var c : td.get().columns()) {
                        node.cols.add(c.name());
                    }
                }
                out.add(node);
                return;
            }
            for (ValueSpecification p : af.parameters()) {
                collectTableToTds(ctx, p, out);
            }
        } else if (n instanceof AppliedProperty ap) {
            collectTableToTds(ctx, ap.receiver(), out);
        } else if (n instanceof LambdaFunction lf) {
            for (ValueSpecification b : lf.body()) {
                collectTableToTds(ctx, b, out);
            }
        } else if (n instanceof com.legend.model.spec.PureCollection pc) {
            for (ValueSpecification e : pc.values()) {
                collectTableToTds(ctx, e, out);
            }
        }
    }

    private static boolean containsCall(ValueSpecification n, String name) {
        if (n instanceof AppliedFunction af) {
            if (name.equals(af.function()
                    .substring(af.function().lastIndexOf(':') + 1))) {
                return true;
            }
            for (ValueSpecification p : af.parameters()) {
                if (containsCall(p, name)) {
                    return true;
                }
            }
        } else if (n instanceof AppliedProperty ap) {
            return containsCall(ap.receiver(), name);
        } else if (n instanceof LambdaFunction lf) {
            for (ValueSpecification b : lf.body()) {
                if (containsCall(b, name)) {
                    return true;
                }
            }
        } else if (n instanceof com.legend.model.spec.PureCollection pc) {
            for (ValueSpecification e : pc.values()) {
                if (containsCall(e, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The engine's set-implementation identity for a class under a
     * mapping (#47 plan printer): {@code [definingMappingName, setId,
     * dbFqn, mainTable]} — the DEFINING mapping is the include-child
     * declaring the set; the default setId is the class FQN with
     * underscores. Loud when the class maps ambiguously. */
    public static String[] rootImpl(ModelContext ctx, String mappingFqn,
            String classFqn) {
        LegacyMappingDefinition md = mapping(ctx, mappingFqn);
        for (LegacyMappingDefinition m : withIncludes(ctx, md)) {
            for (ClassMapping.Relational r : allClassMappings(m)) {
                if (typeMatches(r.className(), classFqn)) {
                    String name = m.qualifiedName().substring(
                            m.qualifiedName().lastIndexOf(':') + 1);
                    String setId = r.setId() != null ? r.setId()
                            : r.className().replace("::", "_");
                    return new String[]{name, setId, mainDbOf(r),
                            mainTableOf(r)};
                }
            }
        }
        throw new NotImplementedException("plan: no class mapping for '"
                + classFqn + "' under '" + mappingFqn + "'");
    }

    /** Whether {@code name} is a VIEW of {@code db} (include closure). */
    public static boolean isView(ModelContext ctx, String db, String name) {
        return db != null && findView(ctx, db, name) != null;
    }

    /** The view's INTERNAL relation tree as a {@link Rel} (the tdg view
     * fetch): root = the view's seed table carrying every plain
     * column-mapped base column, with the view's own join web (column
     * expressions + ~filter) as children — the engine's nestedViewTree.
     * Nested views (a view whose seed is itself a view) stay a named
     * wall. */
    public static Rel viewTree(ModelContext ctx, String db,
            String viewName) {
        return viewExpansion(ctx, db, viewName).tree();
    }

    /** A view's tdg expansion: the internal tree plus the seed (main)
     * table identity and the PLAIN column-mapped view-column &rarr;
     * base-column map (join-navigated columns are absent). */
    public record ViewExpansion(Rel tree, String db, String mainTable,
            java.util.Map<String, String> colToBase) {
    }

    public static ViewExpansion viewExpansion(ModelContext ctx, String db,
            String viewName) {
        DatabaseDefinition.ViewDefinition vd = findView(ctx, db, viewName);
        if (vd == null) {
            throw new NotImplementedException("scanRelations: view '"
                    + viewName + "' not found in '" + db + "'");
        }
        Node root = expandView(ctx, db, vd, true);
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (DatabaseDefinition.ViewDefinition.ViewColumnMapping cm
                : vd.columnMappings()) {
            if (cm.expression()
                    instanceof RelationalOperation.JoinNavigation) {
                continue;
            }
            List<RelationalOperation.ColumnRef> refs = new ArrayList<>();
            columnRefs(cm.expression(), refs);
            if (refs.size() == 1) {
                m.put(cm.name(), refs.get(0).column());
            }
        }
        if (isView(ctx, root.db, root.table)) {
            // NESTED VIEW: the seed is itself a view — compose through
            // the inner expansion (plain-column composition; a nested
            // view with its own join webs stays a named wall)
            if (!root.children.isEmpty()) {
                throw new NotImplementedException("scanRelations: nested"
                        + " view '" + root.table + "' with join webs"
                        + " under '" + viewName + "' pending");
            }
            ViewExpansion inner = viewExpansion(ctx, root.db, root.table);
            java.util.Map<String, String> composed =
                    new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> e : m.entrySet()) {
                String base = inner.colToBase().get(e.getValue());
                if (base == null) {
                    throw new NotImplementedException("scanRelations:"
                            + " nested view column '" + e.getValue()
                            + "' of '" + root.table + "' is not plain"
                            + " column-mapped");
                }
                composed.put(e.getKey(), base);
            }
            java.util.LinkedHashSet<String> cols =
                    new java.util.LinkedHashSet<>(inner.tree().cols());
            root.cols.forEach(c -> cols.add(
                    inner.colToBase().getOrDefault(c, c)));
            Rel merged = new Rel(inner.tree().db(), inner.tree().table(),
                    inner.tree().joinName(), inner.tree().cond(),
                    List.copyOf(cols), inner.tree().children());
            return new ViewExpansion(merged, inner.db(),
                    inner.mainTable(), composed);
        }
        return new ViewExpansion(toRel(root), root.db, root.table, m);
    }

    private static Rel toRel(Node n) {
        List<Rel> kids = new ArrayList<>();
        for (Node c : n.children.values()) {
            kids.add(toRel(c));
        }
        return new Rel(n.db, n.table, n.joinName, n.cond,
                List.copyOf(n.cols), kids);
    }

    private static List<Node> buildRoots(ModelContext ctx,
            LambdaFunction query, String mappingFqn) {
        return buildRoots(ctx, query, mappingFqn, false);
    }

    /** {@code tdgMode}: the #46 data consumer — union-mapped JOIN targets
     * branch a child PER TARGET SET (the engine's testDataGeneration
     * relation tree fetches every set, its own goldens carry the
     * duplicate SQLs); treeString's lineage vocabulary keeps the
     * single-target shape. */
    private static List<Node> buildRoots(ModelContext ctx,
            LambdaFunction query, String mappingFqn, boolean tdgMode) {
        LegacyMappingDefinition md = mapping(ctx, mappingFqn);
        String rootClass = rootClassFqn(query);
        // a UNION-mapped root walks ONCE PER SET, one root table node
        // each, in mapping declaration order (testUnion golden)
        List<ClassMapping.Relational> rootCms =
                rootClassMappings(ctx, md, rootClass);
        List<List<Seg>> paths = new ArrayList<>();
        collectChains(query, paths);
        if (System.getenv("LL_LINEAGE_DEBUG") != null) {
            System.err.println("[scanRelations] paths=" + paths);
        }
        List<Node> roots = new ArrayList<>();
        for (List<Seg> p : paths) {
            if (roots.isEmpty()) {
                for (ClassMapping.Relational cm : rootCms) {
                    Node r = new Node(mainDbOf(cm), mainTableOf(cm), null);
                    roots.add(r);
                    foldClassFilter(ctx, r, cm);
                }
            }
            for (int i = 0; i < rootCms.size(); i++) {
                walk(ctx, md, rootCms.get(i), roots.get(i), p, 0, tdgMode);
            }
        }
        return roots;
    }

    private static List<ClassMapping.Relational> rootClassMappings(
            ModelContext ctx, LegacyMappingDefinition md, String classFqn) {
        List<ClassMapping.Relational> hits = new ArrayList<>();
        for (LegacyMappingDefinition m : withIncludes(ctx, md)) {
            for (ClassMapping.Relational r : allClassMappings(m)) {
                if (typeMatches(r.className(), classFqn)) {
                    hits.add(r);
                }
            }
        }
        if (hits.isEmpty()) {
            throw new NotImplementedException("scanRelations: no class"
                    + " mapping for '" + classFqn + "'");
        }
        // union sets print in SET-ID order (testUnion FirmSet1<FirmSet2 AND
        // testUnionViewOnView neg<nonNeg — declaration order fits only the
        // first golden)
        hits.sort(java.util.Comparator.comparing(
                r -> r.setId() == null ? "" : r.setId()));
        return hits;
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
        return expandView(ctx, dbName, vd, false);
    }

    /** {@code perWebChildren}: each column mapping's join web builds its
     * OWN child chain even when a prefix repeats (the engine's tdg
     * nestedViewTree fetches per web — testSimpleViewRoot pins 5 sqls);
     * the treeString printer keeps the merged form. */
    private static Node expandView(ModelContext ctx, String dbName,
            DatabaseDefinition.ViewDefinition vd, boolean perWebChildren) {
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
                // per-web fork key = the CHAIN IDENTITY: columns sharing
                // one chain share one fetch, distinct chains fork even
                // on a shared prefix (engine per-web fetch counts)
                String suffix = "";
                if (perWebChildren) {
                    StringBuilder sb2 = new StringBuilder("#");
                    for (JoinChainElement el : jn.chain()) {
                        sb2.append(el.joinName()).append('>');
                    }
                    suffix = sb2.toString();
                }
                foldJoinNavigation(ctx, root, dbName, jn, suffix);
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

    /** A set's ~filter joins the tree like the view filter: its join
     * web's tables and condition columns are part of the set's rows. */
    private static void foldClassFilter(ModelContext ctx, Node node,
            ClassMapping.Relational cm) {
        com.legend.model.FilterMapping fm = cm.filter();
        if (fm instanceof com.legend.model.FilterMapping.JoinMediated jm) {
            Node at = joinChain(ctx, null, node, jm.sourceDb(), jm.joins());
            assignFilter(ctx, node, at, jm.sourceDb(), jm.filter());
        } else if (fm instanceof com.legend.model.FilterMapping.Direct d) {
            String db = node.db;
            if (d.filter() instanceof com.legend.model.FilterPointer.Cross c) {
                db = c.db();
            }
            assignFilter(ctx, node, node, db, d.filter());
        }
    }

    private static void foldJoinNavigation(ModelContext ctx, Node root,
            String dbName, RelationalOperation.JoinNavigation jn,
            String keySuffix) {
        Node at = joinChain(ctx, null, root,
                jn.databaseName() != null ? jn.databaseName() : dbName,
                jn.chain(), keySuffix);
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
            ClassMapping.Relational cm, Node node, List<Seg> path, int i,
            boolean tdgMode) {
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
            // a CLASS-DERIVED property (name = firstName + lastName /
            // qualifiers): expand its BODY's $this chains in place — the
            // engine's scanProperties walks derived definitions the same
            // way
            Derived expanded = derivedChains(ctx, cm, prop.name());
            if (expanded != null) {
                // only the RESULT chain continues the outer path; the
                // body's predicate reads are self-contained leaf demands
                // (splicing them mid-chained 'type' into the tail was the
                // graphFetch qualifier bug)
                for (List<Seg> sub : expanded.results()) {
                    List<Seg> spliced = new ArrayList<>(sub);
                    spliced.addAll(path.subList(next, path.size()));
                    walk(ctx, md, cm, node, spliced, 0, tdgMode);
                }
                for (List<Seg> side : expanded.sides()) {
                    walk(ctx, md, cm, node, side, 0, tdgMode);
                }
                return;
            }
            // a SCALAR leaf that is genuinely unmapped is loud; a mid-hop
            // must resolve
            throw new NotImplementedException("scanRelations: property '"
                    + prop.name() + "' has no property mapping in set '"
                    + cm.className() + "'");
        }
        for (PropertyMapping pm : pms) {
            switch (pm) {
                case PropertyMapping.Column c -> {
                    if (next < path.size()) {
                        throw new NotImplementedException("scanRelations:"
                                + " scalar '" + prop.name()
                                + "' in MID position");
                    }
                    node.cols.add(c.column());
                }
                case PropertyMapping.Expression ex -> {
                    // derived scalar (concat(firstName, lastName)): its
                    // source columns are the demand
                    List<RelationalOperation.ColumnRef> refs =
                            new ArrayList<>();
                    columnRefs(ex.expression(), refs);
                    assignByTable(node, refs);
                }
                case PropertyMapping.EnumeratedColumn ec -> {
                    // the enum decode is value-level — the SOURCE COLUMN
                    // is the lineage, same as a plain Column leaf
                    if (next < path.size()) {
                        throw new NotImplementedException("scanRelations:"
                                + " scalar '" + prop.name()
                                + "' in MID position");
                    }
                    node.cols.add(ec.column());
                }
                case PropertyMapping.Join j -> {
                    ClassMapping.Relational target = targetCm(ctx, md, j,
                            node.table, propertyTargetClass(ctx, cm,
                                    prop.name()));
                    if (st != null && !typeMatches(target.className(),
                            st.classFqn())) {
                        continue;
                    }
                    Node child = joinChain(ctx, md, node, j.database(),
                            j.joins());
                    walk(ctx, md, target, child, path, next, tdgMode);
                    if (tdgMode) {
                        unionSiblings(ctx, md, node, child, target, st,
                                path, next);
                    }
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

    /** #46 union-target expansion: the engine's testDataGeneration tree
     * fetches a union-mapped JOIN target once PER SET — its own goldens
     * carry the duplicate per-set SQLs. Each extra set gets a SIBLING of
     * the walked child (same table/edge) and its own sub-walk. */
    private static void unionSiblings(ModelContext ctx,
            LegacyMappingDefinition md, Node parent, Node child,
            ClassMapping.Relational walked, Seg.SubType st, List<Seg> path,
            int next) {
        List<ClassMapping.Relational> sets;
        try {
            sets = rootClassMappings(ctx, md, walked.className());
        } catch (NotImplementedException e) {
            return;
        }
        if (sets.size() < 2) {
            return;
        }
        int k = 0;
        for (ClassMapping.Relational cm2 : sets) {
            k++;
            if (Objects.equals(cm2.setId(), walked.setId())
                    || st != null
                       && !typeMatches(cm2.className(), st.classFqn())) {
                continue;
            }
            // only SAME-TABLE sibling sets fetch through the cloned edge
            // (a different-table set has no rows reachable via this join
            // — testUnionToUnionMultipleLevels' per-set tables)
            String mt2;
            try {
                mt2 = mainTableOf(cm2);
            } catch (NotImplementedException e) {
                continue;
            }
            if (!mt2.equals(child.table)) {
                continue;
            }
            String key = child.table + "(" + child.joinName + ")[set" + k
                    + "]";
            Node dup = parent.children.computeIfAbsent(key,
                    x -> new Node(child.db, child.table, child.joinName));
            dup.cols.addAll(child.cols);
            dup.cond = child.cond;
            walk(ctx, md, cm2, dup, path, next, true);
        }
    }

    /** The $this chains of a class-derived property's body, or null when
     * the class declares no such derived property (or its body is a
     * function-ref binding). */
    private record Derived(List<List<Seg>> results, List<List<Seg>> sides) {}

    private static Derived derivedChains(ModelContext ctx,
            ClassMapping.Relational cm, String prop) {
        com.legend.model.ClassDefinition cd = classDef(ctx, cm.className());
        if (cd == null) {
            return null;
        }
        for (com.legend.model.ClassDefinition.DerivedPropertyDefinition dp
                : cd.derivedProperties()) {
            if (!dp.name().equals(prop)) {
                continue;
            }
            if (!(dp.realization()
                    instanceof com.legend.model.Realization.Inline inl)) {
                return null;
            }
            // VAR-SCOPED collection: $this chains splice verbatim; an
            // inner lambda var over a scoped source ($this.synonyms
            // ->filter(s|$s.type == $type)) splices its reads UNDER the
            // source chain ([synonyms, type]); qualifier PARAMETERS are
            // out of scope and contribute nothing (the '$type' collision
            // walled the graphFetch qualifier trees)
            List<List<Seg>> sides = new ArrayList<>();
            List<List<Seg>> results = new ArrayList<>();
            java.util.Map<String, List<Seg>> scope = new java.util.HashMap<>();
            scope.put("this", List.of());
            for (ValueSpecification b : inl.body()) {
                scopedChains(b, scope, sides);
            }
            // the RESULT chain: the (last) body expression's pipe seen
            // through multiplicity wrappers and filters
            ValueSpecification last = inl.body().get(inl.body().size() - 1);
            List<Seg> res = qualifierResultChain(last, scope);
            if (res != null && !res.isEmpty()) {
                results.add(res);
                sides.removeIf(s -> s.equals(res));
            }
            return new Derived(results, sides);
        }
        return null;
    }

    private static List<Seg> qualifierResultChain(ValueSpecification b,
            java.util.Map<String, List<Seg>> scope) {
        ValueSpecification cur = b;
        while (cur instanceof AppliedFunction af && !af.parameters().isEmpty()
                && java.util.Set.of("toOne", "first", "head", "filter",
                        "toOneMany").contains(simple(af.function()))) {
            cur = af.parameters().get(0);
        }
        return scopedChainOf(cur, scope);
    }

    /** Boolean/comparison/arithmetic natives recurse into their operands —
     * chainOf's qualified-property arm must not hop on them ('equal' and
     * 'times' tails: averageEmployeesAge = ...->average() * 2.0). */
    private static final java.util.Set<String> PRED_OPS = java.util.Set.of(
            "equal", "notEqual", "and", "or", "not", "lessThan",
            "lessThanEqual", "greaterThan", "greaterThanEqual", "in",
            "isEmpty", "isNotEmpty", "contains", "startsWith", "endsWith",
            "times", "multiply", "plus", "minus", "divide", "rem", "mod");

    private static void scopedChains(ValueSpecification n,
            java.util.Map<String, List<Seg>> scope, List<List<Seg>> out) {
        if (n instanceof AppliedFunction bf
                && PRED_OPS.contains(simple(bf.function()))) {
            bf.parameters().forEach(p -> scopedChains(p, scope, out));
            return;
        }
        List<Seg> c = scopedChainOf(n, scope);
        if (c != null) {
            if (!c.isEmpty()) {
                out.add(c);
            }
            return;
        }
        switch (n) {
            case AppliedFunction af -> {
                if (af.parameters().size() == 2
                        && af.parameters().get(1) instanceof LambdaFunction lam
                        && lam.parameters().size() == 1) {
                    List<Seg> src = scopedChainOf(af.parameters().get(0), scope);
                    if (src != null) {
                        if (!src.isEmpty()) {
                            out.add(src);
                        }
                        java.util.Map<String, List<Seg>> inner =
                                new java.util.HashMap<>(scope);
                        inner.put(lam.parameters().get(0).name(), src);
                        lam.body().forEach(x -> scopedChains(x, inner, out));
                        return;
                    }
                }
                af.parameters().forEach(p -> scopedChains(p, scope, out));
            }
            case AppliedProperty ap -> scopedChains(ap.receiver(), scope, out);
            case LambdaFunction lf -> lf.body()
                    .forEach(x -> scopedChains(x, scope, out));
            case PureCollection pc -> pc.values()
                    .forEach(v -> scopedChains(v, scope, out));
            default -> { }
        }
    }

    /** {@link #chainOf} restricted to roots IN SCOPE, prefixed by the
     * root's own chain; null when the root var is unscoped (a qualifier
     * parameter) or the node is not a chain. */
    private static List<Seg> scopedChainOf(ValueSpecification n,
            java.util.Map<String, List<Seg>> scope) {
        String root = rootVarOf(n);
        if (root == null || !scope.containsKey(root)) {
            return null;
        }
        List<Seg> tail = chainOf(n);
        if (tail == null) {
            return null;
        }
        List<Seg> full = new ArrayList<>(scope.get(root));
        full.addAll(tail);
        return full;
    }

    private static String rootVarOf(ValueSpecification n) {
        return switch (n) {
            case Variable v -> v.name();
            case AppliedProperty ap -> rootVarOf(ap.receiver());
            case AppliedFunction af -> af.parameters().isEmpty() ? null
                    : rootVarOf(af.parameters().get(0));
            default -> null;
        };
    }

    /** The parsed class definition for an as-written class spelling. */
    private static com.legend.model.ClassDefinition classDef(ModelContext ctx,
            String written) {
        var direct = ctx.findClassDefinition(written);
        if (direct.isPresent()) {
            return direct.get();
        }
        // as-written short names: resolve by unambiguous tail over the
        // element index
        com.legend.model.ClassDefinition hit = null;
        for (String fqn : ctx.elementFqns()) {
            if (typeMatches(written, fqn)) {
                var cd = ctx.findClassDefinition(fqn);
                if (cd.isPresent()) {
                    if (hit != null) {
                        return null;   // ambiguous: stay loud upstream
                    }
                    hit = cd.get();
                }
            }
        }
        return hit;
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
        return joinChain(ctx, md, parent, db, joins, "");
    }

    private static Node joinChain(ModelContext ctx, LegacyMappingDefinition md,
            Node parent, String db, List<JoinChainElement> joins,
            String keySuffix) {
        Node cur = parent;
        for (JoinChainElement el : joins) {
            final Node at = cur;
            String dbName = el.databaseName() != null ? el.databaseName() : db;
            DatabaseDefinition.JoinDefinition jd = joinDef(ctx, dbName,
                    el.joinName());
            Set<String> tables = new LinkedHashSet<>();
            List<RelationalOperation.ColumnRef> refs = new ArrayList<>();
            List<String> targetCols = new ArrayList<>();
            columnRefs(jd.operation(), refs, targetCols);
            for (RelationalOperation.ColumnRef r : refs) {
                tables.add(bare(r.table()));
            }
            // SELF-JOIN: every plain ref spells the parent side, {target}
            // refs the child — same table, distinct node
            String other = tables.stream()
                    .filter(t -> !t.equals(at.table))
                    .findFirst().orElseGet(() -> {
                        if (!targetCols.isEmpty()) {
                            return at.table;
                        }
                        throw new NotImplementedException("scanRelations:"
                                + " self-join '" + el.joinName()
                                + "' carries no {target} side");
                    });
            String otherDb = refs.stream()
                    .filter(r -> bare(r.table()).equals(other))
                    .map(RelationalOperation.ColumnRef::databaseName)
                    .filter(Objects::nonNull).findFirst().orElse(dbName);
            Node child = at.children.computeIfAbsent(
                    other + "(" + el.joinName() + ")" + keySuffix,
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
            child.cols.addAll(targetCols);
            cur = child;
        }
        return cur;
    }

    private static void columnRefs(RelationalOperation op,
            List<RelationalOperation.ColumnRef> out) {
        columnRefs(op, out, null);
    }

    /** {@code targetCols} non-null accepts {@code {target}.COL} refs
     * (self-join vocabulary); null keeps them a loud wall (view
     * expressions never carry a target side). */
    private static void columnRefs(RelationalOperation op,
            List<RelationalOperation.ColumnRef> out,
            List<String> targetCols) {
        switch (op) {
            case RelationalOperation.ColumnRef cr -> out.add(cr);
            case RelationalOperation.TargetColumnRef tr -> {
                if (targetCols == null) {
                    throw new NotImplementedException("scanRelations:"
                            + " {target} reference outside a join"
                            + " condition");
                }
                targetCols.add(tr.column());
            }
            case RelationalOperation.Comparison c -> {
                columnRefs(c.left(), out, targetCols);
                columnRefs(c.right(), out, targetCols);
            }
            case RelationalOperation.BooleanOp b -> {
                columnRefs(b.left(), out, targetCols);
                columnRefs(b.right(), out, targetCols);
            }
            case RelationalOperation.Group g ->
                    columnRefs(g.inner(), out, targetCols);
            case RelationalOperation.IsNull n ->
                    columnRefs(n.operand(), out, targetCols);
            case RelationalOperation.IsNotNull n ->
                    columnRefs(n.operand(), out, targetCols);
            case RelationalOperation.FunctionCall f -> {
                for (RelationalOperation a : f.args()) {
                    columnRefs(a, out, targetCols);
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
    /** The declared class of a property (deep through supers), null when
     * unresolvable — the join-target disambiguator. */
    private static String propertyTargetClass(ModelContext ctx,
            ClassMapping.Relational cm, String prop) {
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        q.add(cm.className());
        while (!q.isEmpty()) {
            com.legend.model.ClassDefinition cd = classDef(ctx, q.poll());
            if (cd == null) {
                continue;
            }
            for (var p : cd.properties()) {
                if (p.name().equals(prop)
                        && p.type() instanceof com.legend.model.TypeExpression.NameRef nr) {
                    return nr.name();
                }
            }
            for (com.legend.model.TypeExpression s : cd.superClasses()) {
                if (s instanceof com.legend.model.TypeExpression.NameRef snr
                        && seen.add(snr.name())) {
                    q.add(snr.name());
                }
            }
            // association ends are class properties semantically
            var end = ctx.findAssociationEnd(cd.qualifiedName(), prop);
            if (end.isPresent() && end.get().targetClass()
                    instanceof com.legend.model.TypeExpression.NameRef anr) {
                return anr.name();
            }
        }
        return null;
    }

    private static ClassMapping.Relational targetCm(ModelContext ctx,
            LegacyMappingDefinition md, PropertyMapping.Join j,
            String fromTable, String targetClassHint) {
        if (j.targetSetId() != null) {
            return classMappingFor(ctx, md, null, j.targetSetId());
        }
        JoinChainElement last = j.joins().get(j.joins().size() - 1);
        DatabaseDefinition.JoinDefinition jd = joinDef(ctx,
                last.databaseName() != null ? last.databaseName()
                        : j.database(), last.joinName());
        List<RelationalOperation.ColumnRef> refs = new ArrayList<>();
        // {target} refs tolerated: table inference rides the plain side
        // (a self-join's target table IS the plain side's table)
        columnRefs(jd.operation(), refs, new ArrayList<>());
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
        // prefer sets on OTHER tables — but a SELF-JOIN's target IS the
        // from-table, so never empty the candidate list
        List<ClassMapping.Relational> nonSelf = hits.stream()
                .filter(cm -> !mainTableOf(cm).equals(bare(fromTable)))
                .toList();
        if (!nonSelf.isEmpty()) {
            hits = new ArrayList<>(nonSelf);
        }
        if (hits.size() > 1 && targetClassHint != null) {
            // several classes share the join's table: the PROPERTY's
            // declared type picks the set (engine findPropertyMapping
            // resolves per receiver class)
            List<ClassMapping.Relational> byClass = hits.stream()
                    .filter(cm -> typeMatches(cm.className(), targetClassHint)
                            || typeMatches(targetClassHint, cm.className()))
                    .toList();
            if (byClass.size() == 1) {
                return byClass.get(0);
            }
        }
        if (hits.size() != 1) {
            throw new NotImplementedException("scanRelations: join target of"
                    + " '" + j.propertyName() + "' is ambiguous ("
                    + hits.size() + " sets: " + hits.stream()
                            .map(h -> h.className() + "[" + h.setId() + "]")
                            .toList() + "; hint=" + targetClassHint + ")");
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
            // graphFetch trees desugar to ColSpecs (function1 = x|$x.prop,
            // function2 = the lambda-wrapped nested sub-tree): chains
            // compose parent-first — product{name} contributes [product]
            // and [product, name]
            case com.legend.model.spec.ColSpecArray ca ->
                    collectTreeChains(ca, List.of(), out);
            case com.legend.model.spec.ColSpec cs -> collectTreeChains(
                    new com.legend.model.spec.ColSpecArray(List.of(cs)),
                    List.of(), out);
            default -> { }
        }
    }

    private static void collectTreeChains(com.legend.model.spec.ColSpecArray tree,
            List<Seg> parent, List<List<Seg>> out) {
        for (com.legend.model.spec.ColSpec cs : tree.colSpecs()) {
            List<Seg> hop = null;
            if (cs.function1() != null && cs.function1().body().size() == 1) {
                List<Seg> c = chainOf(cs.function1().body().get(0));
                if (c != null && !c.isEmpty()) {
                    hop = c;
                }
            }
            if (System.getenv("LL_LINEAGE_DEBUG") != null) {
                System.err.println("[treeChains] name=" + cs.name()
                        + " f1=" + (cs.function1() == null ? "null"
                                : cs.function1().body())
                        + " hop=" + hop);
            }
            if (hop == null) {
                continue;
            }
            List<Seg> full = new ArrayList<>(parent);
            full.addAll(hop);
            out.add(full);
            if (cs.function2() != null) {
                for (var b : cs.function2().body()) {
                    if (b instanceof com.legend.model.spec.ColSpecArray sub) {
                        collectTreeChains(sub, full, out);
                    }
                }
            }
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
                        .noneMatch(a -> a instanceof LambdaFunction)
                && af.parameters().stream().skip(1)
                        .noneMatch(ScanRelations::carriesChain)) {
            // a call whose extras are chain-free non-lambdas over a chain
            // is a QUALIFIED PROPERTY hop (milestoned dates:
            // product($businessDate)) — an OPERATOR over chains
            // (firstName + lastName) is NOT: its operand chains collect
            // separately. A non-property still lands on the walk's loud
            // unmapped wall, never a silently wrong tree.
            List<Seg> base = chainOf(af.parameters().get(0));
            if (base == null) {
                return null;
            }
            base.add(new Seg.Prop(simple(af.function())));
            return base;
        }
        return null;
    }

    /** Whether the expression contains a NON-EMPTY var-rooted chain. */
    private static boolean carriesChain(ValueSpecification n) {
        List<List<Seg>> probe = new ArrayList<>();
        collectChains(n, probe);
        return !probe.isEmpty();
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
