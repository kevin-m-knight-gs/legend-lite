import java.sql.*;

public class Catalog2 {
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
        } catch (Exception e) { System.out.println("ERR: " + e.getMessage()); }
        System.out.println();
    }

    public static void main(String[] a) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        c = DriverManager.getConnection("jdbc:duckdb:");

        // columns available
        dump("F0 duckdb_functions columns", "SELECT column_name, data_type FROM (DESCRIBE SELECT * FROM duckdb_functions())");

        // ---- THE FILTER, applied cumulatively to DISTINCT NAMES ----
        dump("F1 all distinct names by type", "SELECT function_type, count(DISTINCT function_name) FROM duckdb_functions() GROUP BY 1 ORDER BY 2 DESC");

        dump("F2 drop non-scalar/aggregate/macro", "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type IN ('scalar','aggregate','macro')");

        dump("F3 pg_catalog compat shims", "SELECT function_type, count(DISTINCT function_name) FROM duckdb_functions() WHERE schema_name='pg_catalog' GROUP BY 1");
        dump("F3b pg_catalog names", "SELECT DISTINCT function_name FROM duckdb_functions() WHERE schema_name='pg_catalog' ORDER BY 1");

        dump("F4 __-prefixed internal", "SELECT DISTINCT function_name FROM duckdb_functions() WHERE function_name LIKE '\\_\\_%' ESCAPE '\\' ORDER BY 1");

        dump("F5 operator-symbol names", "SELECT DISTINCT function_name FROM duckdb_functions() WHERE NOT regexp_full_match(function_name, '^[a-z_][a-z0-9_]*$') ORDER BY 1");

        // aggregate suffix families (_if, arg_, w/ 'w' variants) — check pattern families
        dump("F6 aggregate names", "SELECT DISTINCT function_name FROM duckdb_functions() WHERE function_type='aggregate' ORDER BY 1");

        dump("F7 macro names", "SELECT DISTINCT function_name FROM duckdb_functions() WHERE function_type='macro' ORDER BY 1");

        dump("F8 table function names", "SELECT DISTINCT function_name FROM duckdb_functions() WHERE function_type IN ('table','table_macro') ORDER BY 1");

        dump("F9 pragma names", "SELECT DISTINCT function_name FROM duckdb_functions() WHERE function_type='pragma' ORDER BY 1");

        // scalar prefix families — where the 611 goes
        dump("F10 scalar name prefix families", "SELECT regexp_extract(function_name, '^([a-z]+)_', 1) AS pfx, count(DISTINCT function_name) AS names FROM duckdb_functions() WHERE function_type='scalar' AND function_name LIKE '%\\_%' ESCAPE '\\' GROUP BY 1 HAVING count(DISTINCT function_name) >= 4 ORDER BY 2 DESC");

        // alias detection: same description => alias family
        dump("F11 alias families (same description, scalar)", "SELECT count(*) AS desc_groups, sum(n) AS names_in_groups, sum(n-1) AS redundant_aliases FROM (SELECT description, count(DISTINCT function_name) AS n FROM duckdb_functions() WHERE function_type='scalar' AND description IS NOT NULL AND description <> '' GROUP BY 1 HAVING count(DISTINCT function_name) > 1)");

        dump("F12 distinct scalar names with a description", "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type='scalar' AND description IS NOT NULL AND description <> ''");

        // type-parametric families (list_/array_/map_/struct_/union_/json_/bit_/blob-ish/uuid)
        dump("F13 semi-structured families (scalar)", "SELECT CASE WHEN function_name LIKE 'list\\_%' ESCAPE '\\' THEN 'list_' WHEN function_name LIKE 'array\\_%' ESCAPE '\\' THEN 'array_' WHEN function_name LIKE 'map\\_%' ESCAPE '\\' THEN 'map_' WHEN function_name LIKE 'struct\\_%' ESCAPE '\\' THEN 'struct_' WHEN function_name LIKE 'union\\_%' ESCAPE '\\' THEN 'union_' WHEN function_name LIKE 'json%' THEN 'json' WHEN function_name LIKE 'st\\_%' ESCAPE '\\' THEN 'st_(spatial)' ELSE 'other' END AS fam, count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type='scalar' GROUP BY 1 ORDER BY 2 DESC");

        // return/param types that Pure has no mapping for
        dump("F14 scalar fns whose return type is exotic", "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type='scalar' AND (return_type LIKE '%UNION%' OR return_type LIKE '%BIT%' OR return_type LIKE '%UUID%' OR return_type LIKE '%ENUM%' OR return_type LIKE '%INTERVAL%' OR return_type LIKE '%HUGEINT%' OR return_type LIKE 'U%INT%' OR return_type LIKE '%BLOB%' OR return_type LIKE '%STRUCT%' OR return_type LIKE '%MAP%' OR return_type LIKE '%POINTER%')");

        // Pure-mappable return types only
        dump("F15 scalar fns with a Pure-mappable return type", "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type='scalar' AND return_type IN ('VARCHAR','BIGINT','INTEGER','SMALLINT','TINYINT','DOUBLE','FLOAT','DECIMAL','BOOLEAN','DATE','TIMESTAMP','TIMESTAMP WITH TIME ZONE','TIME','JSON')");

        dump("F16 aggregate fns with a Pure-mappable return type", "SELECT count(DISTINCT function_name) FROM duckdb_functions() WHERE function_type='aggregate' AND return_type IN ('VARCHAR','BIGINT','INTEGER','SMALLINT','TINYINT','DOUBLE','FLOAT','DECIMAL','BOOLEAN','DATE','TIMESTAMP','TIMESTAMP WITH TIME ZONE','TIME','JSON')");

        // THE CUMULATIVE FILTER
        dump("F17 CUMULATIVE FILTER — scalar+aggregate, main schema, no __, identifier-named, described",
             "SELECT function_type, count(DISTINCT function_name) FROM duckdb_functions() "
           + "WHERE function_type IN ('scalar','aggregate') AND schema_name='main' "
           + "AND NOT function_name LIKE '\\_\\_%' ESCAPE '\\' "
           + "AND regexp_full_match(function_name, '^[a-z_][a-z0-9_]*$') "
           + "AND description IS NOT NULL AND description <> '' GROUP BY 1");

        dump("F18 CUMULATIVE + Pure-mappable return type",
             "SELECT function_type, count(DISTINCT function_name) FROM duckdb_functions() "
           + "WHERE function_type IN ('scalar','aggregate') AND schema_name='main' "
           + "AND NOT function_name LIKE '\\_\\_%' ESCAPE '\\' "
           + "AND regexp_full_match(function_name, '^[a-z_][a-z0-9_]*$') "
           + "AND description IS NOT NULL AND description <> '' "
           + "AND return_type IN ('VARCHAR','BIGINT','INTEGER','SMALLINT','TINYINT','DOUBLE','FLOAT','DECIMAL','BOOLEAN','DATE','TIMESTAMP','TIMESTAMP WITH TIME ZONE','TIME','JSON') GROUP BY 1");

        dump("F19 CUMULATIVE + Pure-mappable return + drop introspection/settings/test",
             "SELECT function_type, count(DISTINCT function_name) FROM duckdb_functions() "
           + "WHERE function_type IN ('scalar','aggregate') AND schema_name='main' "
           + "AND NOT function_name LIKE '\\_\\_%' ESCAPE '\\' "
           + "AND regexp_full_match(function_name, '^[a-z_][a-z0-9_]*$') "
           + "AND description IS NOT NULL AND description <> '' "
           + "AND return_type IN ('VARCHAR','BIGINT','INTEGER','SMALLINT','TINYINT','DOUBLE','FLOAT','DECIMAL','BOOLEAN','DATE','TIMESTAMP','TIMESTAMP WITH TIME ZONE','TIME','JSON') "
           + "AND NOT (function_name LIKE 'current\\_%' ESCAPE '\\' OR function_name LIKE '%setting%' OR function_name LIKE 'test\\_%' ESCAPE '\\' OR function_name IN ('version','pg_typeof','typeof','error','icu_sort_key','create_sort_key','can_cast_implicitly','enum_first','enum_last','enum_code','enum_range','enum_range_boundary','stats','txid_current','gen_random_uuid','uuid','nextval','currval','hash')) GROUP BY 1");

        // arity distribution
        dump("F20 arity distribution (scalar overloads)", "SELECT len(parameter_types) AS arity, count(*) FROM duckdb_functions() WHERE function_type='scalar' GROUP BY 1 ORDER BY 1");

        // overload multiplicity for aggregates: why 1177/88
        dump("F21 aggregate overload multiplicity top", "SELECT function_name, count(*) AS overloads FROM duckdb_functions() WHERE function_type='aggregate' GROUP BY 1 ORDER BY 2 DESC LIMIT 12");
        dump("F22 scalar overload multiplicity top", "SELECT function_name, count(*) AS overloads FROM duckdb_functions() WHERE function_type='scalar' GROUP BY 1 ORDER BY 2 DESC LIMIT 12");

        // names appearing under >1 function_type
        dump("F23 names under >1 function_type", "SELECT function_name, string_agg(DISTINCT function_type, ',') FROM duckdb_functions() GROUP BY 1 HAVING count(DISTINCT function_type) > 1 ORDER BY 1");

        c.close();
    }
}
