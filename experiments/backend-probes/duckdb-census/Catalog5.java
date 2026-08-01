import java.sql.*; import java.nio.file.*; import java.util.*;
public class Catalog5 {
  static final String CANDB =
    "SELECT DISTINCT function_name FROM duckdb_functions() "
  + "WHERE function_type IN ('scalar','aggregate') AND schema_name='main' "
  + "AND regexp_full_match(function_name,'^[a-z_][a-z0-9_]*$') "
  + "AND NOT function_name LIKE 'icu\\_%' ESCAPE '\\' "
  + "AND NOT function_name LIKE '\\_\\_%' ESCAPE '\\' "
  + "AND description IS NOT NULL AND description <> ''";
  public static void main(String[] a) throws Exception {
    Class.forName("org.duckdb.DuckDBDriver");
    Connection c = DriverManager.getConnection("jdbc:duckdb:");
    String d="/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/";
    Set<String> cand=new TreeSet<>();
    try(Statement s=c.createStatement();ResultSet r=s.executeQuery(CANDB)){while(r.next())cand.add(r.getString(1));}
    Set<String> em=new TreeSet<>();
    for(String l:Files.readAllLines(Path.of(d+"legend-emitted-names.txt"))){l=l.strip();if(!l.isEmpty())em.add(l);}
    Set<String> cov=new TreeSet<>(cand); cov.retainAll(em);
    Set<String> out=new TreeSet<>(em); out.removeAll(cand);
    System.out.println("J1 CANDIDATE-B (no type criterion) = "+cand.size());
    System.out.println("J2 COVERED                          = "+cov.size());
    System.out.println("J3 MISSING                          = "+(cand.size()-cov.size()));
    System.out.println("J4 emitted OUTSIDE cand-B           = "+out.size()+"  "+out);
    c.close();
  }
}
