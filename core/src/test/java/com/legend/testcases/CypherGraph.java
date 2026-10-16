// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testcases;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The model a dumped graph implies, and its tables (docs/CYPHER_STUDY_2026_10_15.md §8.2): a class per label, a
 * property per key typed by its values, a relationship type as an association between the two classes it joins
 * (its ends named by the type, {@code T} on the start class and {@code T_in} on the end class), realized as a
 * foreign key on the to-one side or a junction table, every node a row of its class's table. The lanes seed the
 * tables from the dump and never interpret a {@code CREATE}. What the inference cannot model yet is said by name
 * ({@link Unsupported}), and the case is SKIP in the ledger with that reason: a node with several labels and a
 * relationship type between several class pairs wait for PR3's inheritance, a list or temporal property for PR2.
 */
public final class CypherGraph {

    private CypherGraph() {
    }

    public static final String DATABASE = "g::DB";
    public static final String MAPPING = "g::Map";
    public static final String RUNTIME = "g::RT";

    /** A graph the harness cannot model yet, and why. */
    public static final class Unsupported extends Exception {
        public Unsupported(String reason) {
            super(reason);
        }
    }

    /** The model's text (classes, associations, the database, the mapping; the lane appends the connection and
     *  the runtime), its tables with their rows, and a key over the dump (two cases with one graph seed once). */
    public record Inferred(String model, List<SqlParity.Table> tables, String graphKey) {
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The class of the nodes without a label. */
    private static final String UNLABELLED = "Unlabelled";

    private static final class Cls {
        final String name;
        final String table;
        final Map<String, String> propertyTypes = new TreeMap<>();
        final Set<String> keys = new HashSet<>();
        final List<CypherAnswers.GraphNode> nodes = new ArrayList<>();
        final Map<String, String> fkColumns = new LinkedHashMap<>();   // end name -> column
        final List<String> ends = new ArrayList<>();                   // "T: g::Dst[0..1];"

        Cls(String name) {
            this.name = name;
            this.table = "n_" + name.toLowerCase(Locale.ROOT);
        }
    }

    private static final class RelType {
        final String name;
        Cls src;
        Cls dst;
        final List<CypherAnswers.GraphRel> rels = new ArrayList<>();

        RelType(String name) {
            this.name = name;
        }
    }

    public static Inferred infer(CypherAnswers.Graph g) throws Unsupported {
        Map<String, Cls> classes = new TreeMap<>();
        Map<Integer, Cls> classOfNode = new HashMap<>();
        Map<String, String> tableOwner = new HashMap<>();
        for (CypherAnswers.GraphNode n : g.nodes()) {
            if (n.labels().size() > 1) {
                throw new Unsupported("harness: a node with several labels (" + String.join(":", n.labels())
                        + ") waits for PR3's inheritance");
            }
            String label = n.labels().isEmpty() ? UNLABELLED : n.labels().get(0);
            if (!n.labels().isEmpty() && label.equals(UNLABELLED)) {
                throw new Unsupported("harness: the label " + UNLABELLED + " is the class of the unlabelled nodes");
            }
            if (!IDENTIFIER.matcher(label).matches()) {
                throw new Unsupported("harness: the label " + label + " is not a Pure identifier");
            }
            Cls c = classes.computeIfAbsent(label, Cls::new);
            String owner = tableOwner.putIfAbsent(c.table, label);
            if (owner != null && !owner.equals(label)) {
                throw new Unsupported("harness: the labels " + owner + " and " + label + " differ only in case");
            }
            c.nodes.add(n);
            classOfNode.put(n.id(), c);
            for (Map.Entry<String, String> p : n.props().entrySet()) {
                if (!IDENTIFIER.matcher(p.getKey()).matches()) {
                    throw new Unsupported("harness: the property " + p.getKey() + " is not a Pure identifier");
                }
                String type = typeOf(p.getValue(), p.getKey());
                String before = c.propertyTypes.putIfAbsent(p.getKey(), type);
                if (before != null && !before.equals(type)) {
                    throw new Unsupported("harness: the property " + label + "." + p.getKey() + " is typed two ways ("
                            + before + ", " + type + ")");
                }
            }
        }
        for (Cls c : classes.values()) {
            Set<String> lower = new HashSet<>();
            for (String p : c.propertyTypes.keySet()) {
                if (!lower.add(p.toLowerCase(Locale.ROOT))) {
                    throw new Unsupported("harness: two properties of " + c.name + " differ only in case (" + p + ")");
                }
            }
        }
        Map<String, RelType> types = new TreeMap<>();
        Map<String, String> typeByLower = new HashMap<>();
        for (CypherAnswers.GraphRel r : g.rels()) {
            if (!IDENTIFIER.matcher(r.type()).matches()) {
                throw new Unsupported("harness: the relationship type " + r.type() + " is not a Pure identifier");
            }
            String other = typeByLower.putIfAbsent(r.type().toLowerCase(Locale.ROOT), r.type());
            if (other != null && !other.equals(r.type())) {
                throw new Unsupported("harness: the relationship types " + other + " and " + r.type() + " differ only"
                        + " in case");
            }
            RelType t = types.computeIfAbsent(r.type(), RelType::new);
            Cls src = classOfNode.get(r.start());
            Cls dst = classOfNode.get(r.end());
            if (t.src == null) {
                t.src = src;
                t.dst = dst;
            } else if (t.src != src || t.dst != dst) {
                throw new Unsupported("harness: the relationship type " + r.type() + " joins several class pairs ("
                        + t.src.name + " to " + t.dst.name + ", " + src.name + " to " + dst.name + "), which waits for"
                        + " PR3's inheritance");
            }
            t.rels.add(r);
        }
        // the associations: one per type, realized by a foreign key on the to-one side, else a junction table
        StringBuilder associations = new StringBuilder();
        StringBuilder joins = new StringBuilder();
        StringBuilder associationMappings = new StringBuilder();
        List<SqlParity.Table> junctions = new ArrayList<>();
        for (RelType t : types.values()) {
            Cls src = java.util.Objects.requireNonNull(t.src);
            Cls dst = java.util.Objects.requireNonNull(t.dst);
            String in = t.name + "_in";
            for (Cls c : List.of(src, dst)) {
                if (c.propertyTypes.containsKey(t.name) || c.propertyTypes.containsKey(in) || c.ends.stream()
                        .anyMatch(e -> e.startsWith(t.name + ":") || e.startsWith(in + ":"))) {
                    throw new Unsupported("harness: the relationship type " + t.name + " collides with a property or"
                            + " another type's end on " + c.name);
                }
            }
            Map<Integer, Integer> out = new HashMap<>();
            Map<Integer, Integer> inDegree = new HashMap<>();
            for (CypherAnswers.GraphRel r : t.rels) {
                out.merge(r.start(), 1, Integer::sum);
                inDegree.merge(r.end(), 1, Integer::sum);
            }
            int maxOut = out.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            int maxIn = inDegree.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            // named apart from the classes: a label may be spelled like a relationship type
            String assoc = "g::R_" + t.name;
            String column = "r_" + t.name.toLowerCase(Locale.ROOT);
            String target = src == dst ? "{target}." : dst.table + ".";
            if (maxOut <= 1) {
                // the start node holds at most one end node: a foreign key on the start class's table
                src.fkColumns.put(t.name, column);
                src.ends.add(t.name + ": g::" + dst.name + "[0..1];");
                dst.ends.add(in + ": g::" + src.name + "[*];");
                joins.append("    Join ").append(t.name).append('(').append(src.table).append('.').append(column)
                        .append(" = ").append(target).append("id)\n");
                associationMappings.append("    ").append(assoc).append(": Relational { AssociationMapping ( ")
                        .append(t.name).append(": [g::DB]@").append(t.name).append(", ").append(in)
                        .append(": [g::DB]@").append(t.name).append(" ) }\n");
            } else if (maxIn <= 1) {
                // the end node holds at most one start node: a foreign key on the end class's table
                String col = column + "_in";
                dst.fkColumns.put(in, col);
                src.ends.add(t.name + ": g::" + dst.name + "[*];");
                dst.ends.add(in + ": g::" + src.name + "[0..1];");
                String left = src == dst ? "{target}." : src.table + ".";
                joins.append("    Join ").append(t.name).append('(').append(dst.table).append('.').append(col)
                        .append(" = ").append(left).append("id)\n");
                associationMappings.append("    ").append(assoc).append(": Relational { AssociationMapping ( ")
                        .append(t.name).append(": [g::DB]@").append(t.name).append(", ").append(in)
                        .append(": [g::DB]@").append(t.name).append(" ) }\n");
            } else {
                // many to many: a junction table and a join chain
                String junction = column;
                List<List<Object>> rows = new ArrayList<>();
                for (CypherAnswers.GraphRel r : t.rels) {
                    rows.add(List.of((long) r.start(), (long) r.end()));
                }
                junctions.add(new SqlParity.Table(junction, List.of(new SqlParity.Column("src_id", "BIGINT"),
                        new SqlParity.Column("dst_id", "BIGINT")), rows));
                src.ends.add(t.name + ": g::" + dst.name + "[*];");
                dst.ends.add(in + ": g::" + src.name + "[*];");
                joins.append("    Join ").append(t.name).append("_a(").append(src.table).append(".id = ")
                        .append(junction).append(".src_id)\n");
                joins.append("    Join ").append(t.name).append("_b(").append(junction).append(".dst_id = ")
                        .append(dst.table).append(".id)\n");
                associationMappings.append("    ").append(assoc).append(": Relational { AssociationMapping ( ")
                        .append(t.name).append(": [g::DB]@").append(t.name).append("_a > @").append(t.name)
                        .append("_b, ").append(in).append(": [g::DB]@").append(t.name).append("_b > @")
                        .append(t.name).append("_a ) }\n");
            }
            associations.append("Association ").append(assoc).append("\n{\n");
            for (Cls c : src == dst ? List.of(src) : List.of(src, dst)) {
                for (String e : c.ends) {
                    if (e.startsWith(t.name + ":") || e.startsWith(in + ":")) {
                        associations.append("    ").append(e).append('\n');
                    }
                }
            }
            associations.append("}\n");
        }
        // the classes, the tables and the mapping
        StringBuilder pure = new StringBuilder("###Pure\n");
        StringBuilder tablesDdl = new StringBuilder();
        StringBuilder mapping = new StringBuilder();
        List<SqlParity.Table> tables = new ArrayList<>();
        for (Cls c : classes.values()) {
            pure.append("Class g::").append(c.name).append("\n{\n");
            List<SqlParity.Column> columns = new ArrayList<>();
            columns.add(new SqlParity.Column("id", "BIGINT"));
            StringBuilder ddl = new StringBuilder("    Table " + c.table + " (id BIGINT PRIMARY KEY");
            mapping.append("    g::").append(c.name).append(": Relational\n    {\n        ~mainTable [g::DB] ")
                    .append(c.table).append('\n');
            List<String> propertyMappings = new ArrayList<>();
            for (Map.Entry<String, String> p : c.propertyTypes.entrySet()) {
                String column = "p_" + p.getKey().toLowerCase(Locale.ROOT);
                pure.append("    ").append(p.getKey()).append(": ").append(p.getValue()).append("[0..1];\n");
                columns.add(new SqlParity.Column(column, sqlType(p.getValue())));
                ddl.append(", ").append(column).append(' ').append(relationalType(p.getValue()));
                propertyMappings.add("        " + p.getKey() + ": [g::DB] " + c.table + "." + column);
            }
            for (String column : c.fkColumns.values()) {
                columns.add(new SqlParity.Column(column, "BIGINT"));
                ddl.append(", ").append(column).append(" BIGINT");
            }
            pure.append("}\n");
            ddl.append(")\n");
            tablesDdl.append(ddl);
            mapping.append(String.join(",\n", propertyMappings)).append(propertyMappings.isEmpty() ? "" : "\n")
                    .append("    }\n");
            List<List<Object>> rows = new ArrayList<>();
            Map<Integer, Map<String, Long>> fks = new HashMap<>();
            for (RelType t : types.values()) {
                for (CypherAnswers.GraphRel r : t.rels) {
                    String col = java.util.Objects.requireNonNull(t.src).fkColumns.get(t.name);
                    if (col != null && t.src == c) {
                        fks.computeIfAbsent(r.start(), k -> new HashMap<>()).put(col, (long) r.end());
                    }
                    String colIn = java.util.Objects.requireNonNull(t.dst).fkColumns.get(t.name + "_in");
                    if (colIn != null && t.dst == c) {
                        fks.computeIfAbsent(r.end(), k -> new HashMap<>()).put(colIn, (long) r.start());
                    }
                }
            }
            for (CypherAnswers.GraphNode n : c.nodes) {
                List<Object> row = new ArrayList<>();
                row.add((long) n.id());
                for (Map.Entry<String, String> p : c.propertyTypes.entrySet()) {
                    String text = n.props().get(p.getKey());
                    row.add(text == null ? null : cell(text));
                }
                Map<String, Long> fk = fks.getOrDefault(n.id(), Map.of());
                for (String column : c.fkColumns.values()) {
                    row.add(fk.get(column));
                }
                rows.add(row);
            }
            tables.add(new SqlParity.Table(c.table, columns, rows));
        }
        for (SqlParity.Table j : junctions) {
            tablesDdl.append("    Table ").append(j.name()).append(" (src_id BIGINT, dst_id BIGINT)\n");
            tables.add(j);
        }
        String model = pure + associations.toString() + "###Relational\nDatabase " + DATABASE + "\n(\n" + tablesDdl
                + joins + ")\n###Mapping\nMapping " + MAPPING + "\n(\n" + mapping + associationMappings + ")\n";
        return new Inferred(model, tables, graphKey(g));
    }

    /** The model with its connection and runtime, as a lane runs it. */
    public static String model(Inferred inferred, String connection) {
        return inferred.model() + "###Connection\nRelationalDatabaseConnection g::Conn\n{\n    " + connection
                + "\n}\n###Runtime\nRuntime " + RUNTIME + "\n{\n    mappings: [ " + MAPPING + " ];\n    connections: [ "
                + DATABASE + ": [ environment: g::Conn ] ];\n}\n";
    }

    private static String typeOf(String valueText, String key) throws Unsupported {
        CypherValues.Value v = CypherValues.parse(valueText);
        return switch (v) {
            case CypherValues.Int i -> "Integer";
            case CypherValues.Flt f -> "Float";
            case CypherValues.Str s -> "String";
            case CypherValues.Bool b -> "Boolean";
            case CypherValues.Opaque o when o.text().startsWith("Date('") -> "StrictDate";
            case CypherValues.Opaque o when LOCAL_DATE_TIME.matcher(o.text()).matches() -> {
                String fraction = LOCAL_DATE_TIME.matcher(o.text()).replaceAll("$2");
                if (fraction.length() > 6 && !fraction.substring(6).matches("0+")) {
                    throw new Unsupported("harness: the property " + key + " holds a date-time of sub-microsecond"
                            + " precision (" + valueText + ")");
                }
                yield "DateTime";
            }
            default -> throw new Unsupported("harness: the property " + key + " holds a " + v.getClass()
                    .getSimpleName().toLowerCase(Locale.ROOT) + " (" + valueText + "), a kind the model does not"
                    + " hold yet (a time, a zoned date-time, a duration)");
        };
    }

    /** A local date-time as the oracle spells it: no zone offset after the time. */
    private static final java.util.regex.Pattern LOCAL_DATE_TIME = java.util.regex.Pattern.compile(
            "DateTime\\('(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})\\.(\\d{1,9})'\\)");

    private static Object cell(String text) {
        CypherValues.Value v = CypherValues.parse(text);
        return switch (v) {
            case CypherValues.Int i -> i.value().longValueExact();
            case CypherValues.Flt f -> f.value();
            case CypherValues.Str s -> s.value();
            case CypherValues.Bool b -> b.value();
            // a date or date-time: the text the seed binds (java.sql.Date.valueOf, Timestamp.valueOf)
            case CypherValues.Opaque o when o.text().startsWith("Date('") -> o.text().substring(6,
                    o.text().length() - 2);
            case CypherValues.Opaque o when LOCAL_DATE_TIME.matcher(o.text()).matches() -> {
                java.util.regex.Matcher m = LOCAL_DATE_TIME.matcher(o.text());
                m.matches();
                String fraction = m.group(2);
                yield m.group(1).replace('T', ' ') + "." + (fraction.length() > 6 ? fraction.substring(0, 6)
                        : fraction);
            }
            default -> throw new IllegalStateException("a cell of a kind the inference refused: " + text);
        };
    }

    private static String sqlType(String pureType) {
        return switch (pureType) {
            case "Integer" -> "BIGINT";
            case "Float" -> "DOUBLE PRECISION";
            case "String" -> "VARCHAR(4000)";
            case "Boolean" -> "BOOLEAN";
            case "StrictDate" -> "DATE";
            case "DateTime" -> "TIMESTAMP";
            default -> throw new IllegalStateException(pureType);
        };
    }

    private static String relationalType(String pureType) {
        return switch (pureType) {
            case "Integer" -> "BIGINT";
            case "Float" -> "DOUBLE";
            case "String" -> "VARCHAR(4000)";
            case "Boolean" -> "BIT";
            case "StrictDate" -> "DATE";
            case "DateTime" -> "TIMESTAMP";
            default -> throw new IllegalStateException(pureType);
        };
    }

    /** A key over the dump: two cases whose graphs are one graph seed the lane once. */
    private static String graphKey(CypherAnswers.Graph g) {
        StringBuilder text = new StringBuilder();
        for (CypherAnswers.GraphNode n : g.nodes()) {
            text.append(n.id()).append(' ').append(n.labels()).append(' ').append(n.props()).append('\n');
        }
        for (CypherAnswers.GraphRel r : g.rels()) {
            text.append(r.id()).append(' ').append(r.start()).append("->").append(r.end()).append(' ')
                    .append(r.type()).append(' ').append(r.props()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(
                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
