package com.legend.normalizer;

import com.legend.model.ClassMapping;
import com.legend.model.MappingDefinition;
import com.legend.model.PropertyMapping;
import com.legend.model.RelationalOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * The key text a relational set DECLARES itself &mdash; the metamodel's
 * per-set facts ({@code MappingDefinition.ClassBinding.DeclaredKeys}),
 * captured from the parsed class mapping BEFORE the extends pre-pass
 * merges the parent's in (the engine's {@code resolvePrimaryKey}
 * precedence walks these across the extends chain at query time).
 */
final class SetKeyFacts {

    private SetKeyFacts() {
    }

    /** The set's identity for the declared-keys capture: its id, else
     * the class (the engine's default id derives from it). */
    static String setKey(ClassMapping.Relational r) {
        return r.setId() != null ? r.setId() : r.className();
    }

    /** The key text a set declares itself (pre-extends-merge). */
    static MappingDefinition.ClassBinding.DeclaredKeys declaredKeysOf(
            ClassMapping.Relational r) {
        return new MappingDefinition.ClassBinding.DeclaredKeys(r.distinct(),
                columnNames(r.groupBy()), columnNames(r.primaryKey()),
                mappedColumnNames(r), ownPropertyNames(r));
    }

    /** The property names the set maps itself (pre-inheritance-merge). */
    static List<String> ownPropertyNames(ClassMapping.Relational rcm) {
        List<String> out = new ArrayList<>();
        for (PropertyMapping pm : rcm.propertyMappings()) {
            out.add(pm.propertyName());
        }
        return out;
    }

    /** Column-ref operations' column names (other shapes: no key fact). */
    static List<String> columnNames(List<RelationalOperation> ops) {
        List<String> out = new ArrayList<>();
        for (RelationalOperation op : ops) {
            if (op instanceof RelationalOperation.ColumnRef cr) {
                out.add(cr.column());
            }
        }
        return out;
    }

    /** Direct column property mappings' columns — a ~distinct set's key. */
    static List<String> mappedColumnNames(ClassMapping.Relational rcm) {
        List<String> out = new ArrayList<>();
        for (PropertyMapping pm : rcm.propertyMappings()) {
            if (pm instanceof PropertyMapping.Column pc) {
                out.add(pc.column());
            }
        }
        return out;
    }

}
