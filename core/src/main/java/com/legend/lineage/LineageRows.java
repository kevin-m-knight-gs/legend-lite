// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scanRelations handle's relation tree AS ROWS (harness burn-down group
 * E, 2026-09-03): {@code relation_trees(id)},
 * {@code relation_tree_nodes(id, tree_id, preorder, indent, kind, name,
 * join_label)} and {@code relation_tree_node_columns(node_id, ordinal,
 * name)} — the printed lines of {@link ScanRelations#lines} as data, one
 * node per line in preorder. The rows ride the query under the handle's
 * scope; the database prints them ({@code relationTreeAsString}).
 */
public final class LineageRows {

    private LineageRows() {
    }

    public static Map<String, List<List<String>>> rows(String scope,
            List<ScanRelations.Line> lines) {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        out.put("relation_trees", List.of(List.of(scope)));
        List<List<String>> nodes = new ArrayList<>();
        List<List<String>> cols = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ScanRelations.Line l = lines.get(i);
            String id = scope + "/" + i;
            List<String> row = new ArrayList<>();
            row.add(id);
            row.add(scope);
            row.add(Integer.toString(i));
            row.add("  ".repeat(l.depth()));
            row.add(l.kind());
            row.add(l.name());
            row.add(l.label());
            nodes.add(row);
            for (int k = 0; k < l.cols().size(); k++) {
                cols.add(List.of(id, Integer.toString(k), l.cols().get(k)));
            }
        }
        out.put("relation_tree_nodes", nodes);
        out.put("relation_tree_node_columns", cols);
        return out;
    }
}
