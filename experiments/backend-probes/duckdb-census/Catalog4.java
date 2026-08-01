import java.sql.*;
import java.nio.file.*;
import java.util.*;

public class Catalog4 {
    static final String PT = "('VARCHAR','BIGINT','INTEGER','SMALLINT','TINYINT','DOUBLE','FLOAT',"
        + "'DECIMAL','BOOLEAN','DATE','TIMESTAMP','TIMESTAMP WITH TIME ZONE','TIME','JSON','ANY')";

    static final String CAND =
        "SELECT DISTINCT function_name FROM duckdb_functions() "
      + "WHERE function_type IN ('scalar','aggregate') AND schema_name='main' "
      + "AND regexp_full_match(function_name,'^[a-z_][a-z0-9_]*$') "
      + "AND NOT function_name LIKE 'icu\\_%' ESCAPE '\\' "
      + "AND NOT function_name LIKE '\\_\\_%' ESCAPE '\\' "
      + "AND description IS NOT NULL AND description <> '' "
      + "AND return_type IN " + PT + " "
      + "AND len(list_filter(parameter_types, x -> x NOT IN " + PT + ")) = 0";

    public static void main(String[] a) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        String dir = "/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/";

        Set<String> cand = new TreeSet<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(CAND)) {
            while (rs.next()) cand.add(rs.getString(1));
        }
        Set<String> emitted = new TreeSet<>();
        for (String l : Files.readAllLines(Path.of(dir + "legend-emitted-names.txt"))) {
            l = l.strip(); if (!l.isEmpty()) emitted.add(l);
        }
        Set<String> covered = new TreeSet<>(cand); covered.retainAll(emitted);
        Set<String> missing = new TreeSet<>(cand); missing.removeAll(emitted);
        Set<String> outside = new TreeSet<>(emitted); outside.removeAll(cand);

        System.out.println("H1 CANDIDATE SET (query CAND) = " + cand.size());
        System.out.println("H2 legend-lite emitted names   = " + emitted.size());
        System.out.println("H3 COVERED (cand ∩ emitted)    = " + covered.size());
        System.out.println("H4 MISSING (cand - emitted)    = " + missing.size());
        System.out.println("H5 emitted OUTSIDE cand        = " + outside.size() + "  " + outside);
        System.out.println();
        Files.write(Path.of(dir + "cand.txt"), cand);
        Files.write(Path.of(dir + "missing.txt"), missing);

        // What the missing 'want' set looks like, by family
        Map<String,Integer> fam = new TreeMap<>();
        for (String n : missing) {
            String f = n.startsWith("list_")||n.startsWith("array_") ? "list/array"
                : n.startsWith("map_")||n.startsWith("struct_")||n.startsWith("union_") ? "map/struct/union"
                : n.startsWith("json") ? "json"
                : n.startsWith("regexp") ? "regexp"
                : n.startsWith("bit") ? "bit"
                : "other";
            fam.merge(f, 1, Integer::sum);
        }
        System.out.println("H6 MISSING by family: " + fam);
        System.out.println();
        System.out.println("H7 MISSING (first 120): ");
        int i=0; StringBuilder sb=new StringBuilder();
        for (String n : missing) { sb.append(n).append(" "); if (++i%10==0) sb.append("\n"); if (i>=120) break; }
        System.out.println(sb);
        c.close();
    }
}
