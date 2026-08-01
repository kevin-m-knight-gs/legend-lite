import java.sql.*;
import java.io.*;

public class Catalog {
    static Connection c;

    static void dump(String label, String sql) throws Exception {
        System.out.println("=== " + label + " ===");
        System.out.println("SQL: " + sql);
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData();
            int n = m.getColumnCount();
            StringBuilder h = new StringBuilder();
            for (int i = 1; i <= n; i++) { if (i > 1) h.append("\t"); h.append(m.getColumnName(i)); }
            System.out.println(h);
            int rows = 0;
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) { if (i > 1) b.append("\t"); b.append(String.valueOf(rs.getObject(i)).replace("\t"," ").replace("\n"," ")); }
                System.out.println(b);
                rows++;
            }
            System.out.println("(" + rows + " rows)");
        } catch (Exception e) {
            System.out.println("ERR: " + e.getMessage());
        }
        System.out.println();
    }

    public static void main(String[] a) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        c = DriverManager.getConnection("jdbc:duckdb:");

        dump("version", "SELECT version()");

        // Q1: counts by function_type
        dump("Q1 function_type counts", "SELECT function_type, count(*) AS overloads, count(DISTINCT function_name) AS distinct_names FROM duckdb_functions() GROUP BY 1 ORDER BY 2 DESC");

        // Q2: total
        dump("Q2 totals", "SELECT count(*) AS total_overloads, count(DISTINCT function_name) AS total_distinct_names FROM duckdb_functions()");

        // Q3: internal vs not
        dump("Q3 internal split", "SELECT internal, function_type, count(*) AS overloads, count(DISTINCT function_name) AS names FROM duckdb_functions() GROUP BY 1,2 ORDER BY 1,2");

        // Q4: by schema / database
        dump("Q4 by schema", "SELECT database_name, schema_name, count(*) AS overloads, count(DISTINCT function_name) AS names FROM duckdb_functions() GROUP BY 1,2 ORDER BY 3 DESC");

        // Q5: extensions
        dump("Q5 extensions", "SELECT extension_name, loaded, installed, install_mode FROM duckdb_extensions() ORDER BY 1");
        dump("Q5b loaded extensions", "SELECT count(*) FROM duckdb_extensions() WHERE loaded");

        // Q6: description coverage
        dump("Q6 description coverage", "SELECT (description IS NOT NULL AND description <> '') AS has_desc, count(*) FROM duckdb_functions() GROUP BY 1");

        // Q7: names starting with __ or containing internal markers
        dump("Q7 underscore-prefixed names", "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_name LIKE '\\_\\_%' ESCAPE '\\'");

        // Q8: names by leading char class
        dump("Q8 duckdb_ prefixed introspection", "SELECT function_type, count(DISTINCT function_name) FROM duckdb_functions() WHERE function_name LIKE 'duckdb\\_%' ESCAPE '\\' GROUP BY 1");

        // Q9: pragma-ish
        dump("Q9 pragma/system-ish names", "SELECT function_type, count(DISTINCT function_name) FROM duckdb_functions() WHERE function_name LIKE 'pragma%' OR function_name LIKE '%_info' OR function_name LIKE 'test_%' OR function_name LIKE 'sql%_settings' GROUP BY 1");

        // Q10: aliases / operator-symbol names
        dump("Q10 non-identifier (operator) names", "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE NOT regexp_full_match(function_name, '^[a-z_][a-z0-9_]*$')");

        // Q11: macro types?
        dump("Q11 macro flag", "SELECT function_type, macro_definition IS NOT NULL AS is_macro, count(*) FROM duckdb_functions() GROUP BY 1,2 ORDER BY 1,2");

        // Q12: settings count for reference
        dump("Q12 duckdb_settings count", "SELECT count(*) FROM duckdb_settings()");

        // Q13: keyword count
        dump("Q13 duckdb_keywords count", "SELECT count(*) FROM duckdb_keywords()");

        // Q14: distinct return types on scalar
        dump("Q14 top return types (scalar)", "SELECT return_type, count(*) FROM duckdb_functions() WHERE function_type='scalar' GROUP BY 1 ORDER BY 2 DESC LIMIT 25");

        // Q15: varargs
        dump("Q15 varargs", "SELECT function_type, varargs IS NOT NULL AS has_varargs, count(*) FROM duckdb_functions() GROUP BY 1,2 ORDER BY 1,2");

        // FULL DUMP
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT database_name, schema_name, function_name, function_type, return_type, "
              + "list_aggregate(parameters, 'string_agg', '|') AS parameters, "
              + "list_aggregate(parameter_types, 'string_agg', '|') AS parameter_types, "
              + "varargs, macro_definition, has_side_effects, internal, description, function_oid "
              + "FROM duckdb_functions() ORDER BY function_name, function_oid");
             PrintWriter w = new PrintWriter(new FileWriter("/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/duckdb-functions.tsv"))) {
            ResultSetMetaData m = rs.getMetaData();
            int n = m.getColumnCount();
            StringBuilder h = new StringBuilder();
            for (int i = 1; i <= n; i++) { if (i > 1) h.append("\t"); h.append(m.getColumnName(i)); }
            w.println(h);
            int rows = 0;
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) { if (i > 1) b.append("\t"); Object o = rs.getObject(i); b.append(o == null ? "" : String.valueOf(o).replace("\t"," ").replace("\n"," ")); }
                w.println(b);
                rows++;
            }
            System.out.println("WROTE duckdb-functions.tsv rows=" + rows);
        }
        c.close();
    }
}
