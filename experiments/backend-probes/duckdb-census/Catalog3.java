import java.sql.*;
import java.nio.file.*;
import java.util.*;

public class Catalog3 {
    static Connection c;
    static void dump(String label, String sql) throws Exception {
        System.out.println("=== " + label + " ===");
        System.out.println("SQL: " + sql);
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData(); int n = m.getColumnCount();
            StringBuilder h = new StringBuilder();
            for (int i = 1; i <= n; i++) { if (i > 1) h.append("\t"); h.append(m.getColumnName(i)); }
            System.out.println(h);
            int rows = 0;
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) { if (i > 1) b.append("\t"); b.append(String.valueOf(rs.getObject(i)).replace("\t"," ").replace("\n"," ")); }
                System.out.println(b); rows++;
            }
            System.out.println("(" + rows + " rows)");
        } catch (Exception e) { System.out.println("ERR: " + e.getMessage()); }
        System.out.println();
    }

    public static void main(String[] a) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        c = DriverManager.getConnection("jdbc:duckdb:");

        dump("G1 icu_ family sample", "SELECT DISTINCT function_name FROM duckdb_functions() WHERE function_name LIKE 'icu\\_%' ESCAPE '\\' ORDER BY 1 LIMIT 20");
        dump("G1b icu_ count", "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_name LIKE 'icu\\_%' ESCAPE '\\'");
        dump("G1c icu_ described?", "SELECT (description IS NOT NULL AND description<>'') AS d, count(DISTINCT function_name) FROM duckdb_functions() WHERE function_name LIKE 'icu\\_%' ESCAPE '\\' GROUP BY 1");

        // the 485 'other' scalar names, described, main schema, identifier-named
        dump("G2 THE PORTABLE-SHAPED CANDIDATE SET (scalar)",
            "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type='scalar' AND schema_name='main' "
          + "AND regexp_full_match(function_name,'^[a-z_][a-z0-9_]*$') AND NOT function_name LIKE 'icu\\_%' ESCAPE '\\' "
          + "AND NOT function_name LIKE '\\_\\_%' ESCAPE '\\' AND description IS NOT NULL AND description <> ''");

        // count Pure-typed args: every parameter type must be Pure-mappable too
        String pureTypes = "('VARCHAR','BIGINT','INTEGER','SMALLINT','TINYINT','DOUBLE','FLOAT','DECIMAL','BOOLEAN','DATE','TIMESTAMP','TIMESTAMP WITH TIME ZONE','TIME','JSON','ANY')";
        dump("G3 scalar: ALL params AND return Pure-mappable",
            "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type='scalar' AND schema_name='main' "
          + "AND regexp_full_match(function_name,'^[a-z_][a-z0-9_]*$') AND NOT function_name LIKE 'icu\\_%' ESCAPE '\\' "
          + "AND NOT function_name LIKE '\\_\\_%' ESCAPE '\\' AND description IS NOT NULL AND description <> '' "
          + "AND return_type IN " + pureTypes + " "
          + "AND len(list_filter(parameter_types, x -> x NOT IN " + pureTypes + ")) = 0");

        dump("G4 aggregate: ALL params AND return Pure-mappable",
            "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type='aggregate' AND schema_name='main' "
          + "AND regexp_full_match(function_name,'^[a-z_][a-z0-9_]*$') AND description IS NOT NULL AND description <> '' "
          + "AND return_type IN " + pureTypes + " "
          + "AND len(list_filter(parameter_types, x -> x NOT IN " + pureTypes + ")) = 0");

        dump("G5 list/array/map/struct/json/union scalar names (the collection tier)",
            "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type='scalar' AND schema_name='main' "
          + "AND (function_name LIKE 'list\\_%' ESCAPE '\\' OR function_name LIKE 'array\\_%' ESCAPE '\\' OR function_name LIKE 'map\\_%' ESCAPE '\\' "
          + "OR function_name LIKE 'struct\\_%' ESCAPE '\\' OR function_name LIKE 'union\\_%' ESCAPE '\\' OR function_name LIKE 'json%')");

        // read the legend-lite emitted names file and check each against the catalog
        Path p = Path.of("/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/legend-emitted-names.txt");
        if (Files.exists(p)) {
            List<String> names = new ArrayList<>();
            for (String line : Files.readAllLines(p)) { line = line.strip(); if (!line.isEmpty()) names.add(line.toLowerCase()); }
            System.out.println("=== G6 legend-lite emitted DuckDB names: catalog check ===");
            System.out.println("candidates=" + names.size());
            Set<String> known = new HashSet<>(), unknown = new LinkedHashSet<>();
            Map<String,String> type = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT string_agg(DISTINCT function_type,'/') FROM duckdb_functions() WHERE lower(function_name)=?")) {
                for (String n : names) {
                    ps.setString(1, n);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next(); String t = rs.getString(1);
                        if (t == null) { unknown.add(n); } else { known.add(n); type.put(n, t); }
                    }
                }
            }
            System.out.println("IN CATALOG   = " + known.size());
            System.out.println("NOT IN CATALOG = " + unknown.size() + "  -> " + unknown);
            Map<String,Integer> byType = new TreeMap<>();
            for (var e : type.entrySet()) byType.merge(e.getValue(), 1, Integer::sum);
            System.out.println("by function_type: " + byType);
            System.out.println();
        } else {
            System.out.println("(no legend-emitted-names.txt yet)");
        }
        c.close();
    }
}
