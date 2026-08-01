import java.sql.*;
import java.util.Properties;

/**
 * Databricks JDBC connectivity test.
 *
 * No args      -> offline checks only: driver registers, URL scheme accepted, version reported.
 * With args    -> real connection attempt.
 *   java DbxTest <hostname> <httpPath> <token>
 *   e.g. java DbxTest dbc-xxxx.cloud.databricks.com /sql/1.0/warehouses/abc123 dapi...
 */
public class DbxTest {

    public static void main(String[] args) throws Exception {
        Class.forName("com.databricks.client.jdbc.Driver");
        Driver d = DriverManager.getDriver("jdbc:databricks://example.cloud.databricks.com:443");
        System.out.println("driver class   : " + d.getClass().getName());
        System.out.println("driver version : " + d.getMajorVersion() + "." + d.getMinorVersion());
        System.out.println("accepts URL    : " + d.acceptsURL("jdbc:databricks://h:443/default"));
        System.out.println("jdbc compliant : " + d.jdbcCompliant());

        if (args.length < 3) {
            System.out.println("\n[offline checks passed — pass <host> <httpPath> <token> to attempt a real connection]");
            return;
        }

        String host = args[0], httpPath = args[1], token = args[2];
        String url = "jdbc:databricks://" + host + ":443/default;"
                   + "transportMode=http;ssl=1;httpPath=" + httpPath + ";"
                   + "AuthMech=3;UID=token;ConnCatalog=samples";
        Properties p = new Properties();
        p.setProperty("PWD", token);
        p.setProperty("user", "token");
        p.setProperty("password", token);

        System.out.println("\nconnecting to " + host + " …");
        long t0 = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(url, p)) {
            long ms = System.currentTimeMillis() - t0;
            DatabaseMetaData md = c.getMetaData();
            System.out.println("CONNECTED in " + ms + "ms");
            System.out.println("  product : " + md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
            System.out.println("  driver  : " + md.getDriverName() + " " + md.getDriverVersion());

            // The point of the exercise: can it actually run statements?
            String[] probes = {
                "SELECT 1",
                "SELECT current_version()",
                "SELECT current_catalog(), current_schema()",
                "CREATE TABLE IF NOT EXISTS ll_probe_tmp (id INT, name STRING)",
                "INSERT INTO ll_probe_tmp VALUES (1,'alice'),(2,'bob')",
                "SELECT count(*) FROM ll_probe_tmp",
                "SELECT round(2.5), round(100.5)",
                "SELECT explode(array(1,2,3))",
                "DROP TABLE IF EXISTS ll_probe_tmp",
            };
            for (String sql : probes) {
                try (Statement st = c.createStatement()) {
                    boolean rs = st.execute(sql);
                    String v = "<no rs>";
                    if (rs) try (ResultSet r = st.getResultSet()) {
                        v = r.next() ? String.valueOf(r.getObject(1)) : "<no rows>";
                    }
                    System.out.printf("  OK   %-58s %s%n", trunc(sql), v);
                } catch (Exception e) {
                    System.out.printf("  ERR  %-58s %s%n", trunc(sql), trunc2(e.getMessage()));
                }
            }
        } catch (Exception e) {
            System.out.println("CONNECTION FAILED after " + (System.currentTimeMillis() - t0) + "ms");
            System.out.println("  " + e.getClass().getName());
            System.out.println("  " + e.getMessage());
        }
    }

    static String trunc(String s) { return s.length() > 58 ? s.substring(0, 55) + "..." : s; }
    static String trunc2(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 110 ? s.substring(0, 110) + "..." : s;
    }
}
