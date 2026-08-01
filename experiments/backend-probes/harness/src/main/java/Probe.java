import java.sql.*;
import java.nio.file.*;
import java.util.*;

/**
 * Generic SQL capability probe runner.
 *
 * Usage: java Probe &lt;backend&gt; &lt;probefile&gt; [outfile]
 *   backend  = duckdb | sqlite | h2 | postgres
 *   probefile = TSV lines: ID \t CATEGORY \t SQL      ('#' comments, blank lines skipped)
 *
 * Emits TSV: ID \t CATEGORY \t STATUS \t VALUE_OR_ERROR
 *   STATUS = OK  (statement executed; VALUE = first cell of first row, or &lt;norows&gt;)
 *          = ERR (VALUE = exception message, newlines collapsed, truncated)
 *
 * The SAME probe file runs on every backend, so the outputs are directly
 * comparable cell-by-cell. Value equality is as much the point as OK/ERR:
 * a statement that PARSES on two backends but returns different values is a
 * silent-wrong-answer risk, which is the class of defect that matters most.
 */
public class Probe {

    /**
     * Portable fixture — every backend gets identical rows.
     * SQLite is the exception: it has no DATE literal and no date type, so its
     * dates load as TEXT. That divergence is itself a finding, not a workaround.
     */
    static String[] fixture(String backend) {
        String[] f = FIXTURE.clone();
        if (backend.equals("sqlite")) {
            for (int i = 0; i < f.length; i++) f[i] = f[i].replace("DATE '", "'");
        }
        return f;
    }

    static final String[] FIXTURE = {
        "CREATE TABLE dept (id INTEGER, name VARCHAR(50))",
        "CREATE TABLE emp (id INTEGER, name VARCHAR(50), dept_id INTEGER, sal DOUBLE PRECISION, hired DATE, mgr INTEGER)",
        "CREATE TABLE proj (id INTEGER, emp_id INTEGER, name VARCHAR(50), budget DECIMAL(12,2))",
        "INSERT INTO dept VALUES (1,'Eng'), (2,'Sales'), (3,'Empty')",
        "INSERT INTO emp VALUES (1,'alice',1,100.5,DATE '2020-01-15',NULL),"
            + "(2,'bob',1,90.25,DATE '2021-06-30',1),"
            + "(3,'carol',2,120.75,DATE '2019-11-01',1),"
            + "(4,'dave',2,80.0,DATE '2022-03-20',3),"
            + "(5,'eve',NULL,NULL,NULL,NULL)",
        "INSERT INTO proj VALUES (1,1,'apollo',1000.00),(2,1,'gemini',2000.50),(3,3,'mercury',300.25)",
    };

    public static void main(String[] args) throws Exception {
        String backend = args[0];
        List<String> lines = Files.readAllLines(Paths.get(args[1]));
        StringBuilder out = new StringBuilder();

        Object holder = null;
        Connection conn;
        switch (backend) {
            case "duckdb" -> conn = DriverManager.getConnection("jdbc:duckdb:");
            case "sqlite" -> conn = DriverManager.getConnection("jdbc:sqlite::memory:");
            case "h2" -> conn = DriverManager.getConnection(
                    "jdbc:h2:mem:probe;MODE=LEGACY;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "");
            case "postgres" -> {
                var cls = Class.forName("io.zonky.test.db.postgres.embedded.EmbeddedPostgres");
                var builder = cls.getMethod("builder").invoke(null);
                Object pg = builder.getClass().getMethod("start").invoke(builder);
                holder = pg;
                String url = (String) pg.getClass().getMethod("getJdbcUrl", String.class, String.class)
                        .invoke(pg, "postgres", "postgres");
                conn = DriverManager.getConnection(url);
            }
            case "mariadb" -> {
                var cfgB = ch.vorburger.mariadb4j.DBConfigurationBuilder.newBuilder();
                cfgB.setPort(0);
                var cfg = cfgB.build();
                var db = ch.vorburger.mariadb4j.DB.newEmbeddedDB(cfg);
                db.start();
                db.createDB("probe");
                holder = db;
                conn = DriverManager.getConnection(cfg.getURL("probe"), "root", "");
            }
            default -> throw new IllegalArgumentException("unknown backend " + backend);
        }

        // Fixture — failures here are reported, not fatal, so a backend that
        // rejects one DDL still yields results for every probe that can run.
        for (String ddl : fixture(backend)) {
            try (Statement st = conn.createStatement()) {
                st.execute(ddl);
            } catch (Exception e) {
                out.append("FIXTURE\tsetup\tERR\t").append(clean(e.getMessage())).append('\n');
            }
        }

        int ok = 0, err = 0;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            String id = p[0], cat = p[1], sql = p[2];
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(15);
                boolean hasRs = st.execute(sql);
                String val = "<nors>";
                if (hasRs) {
                    try (ResultSet rs = st.getResultSet()) {
                        if (rs.next()) {
                            Object o = rs.getObject(1);
                            val = render(o);
                        } else {
                            val = "<norows>";
                        }
                    }
                }
                out.append(id).append('\t').append(cat).append("\tOK\t").append(clean(val)).append('\n');
                ok++;
            } catch (Throwable e) {
                String m = e.getMessage();
                out.append(id).append('\t').append(cat).append("\tERR\t")
                   .append(clean(m == null ? e.getClass().getSimpleName() : m)).append('\n');
                err++;
            }
        }

        String dest = args.length > 2 ? args[2] : null;
        if (dest != null) Files.writeString(Paths.get(dest), out.toString());
        else System.out.print(out);
        System.err.println("[" + backend + "] OK=" + ok + " ERR=" + err);

        try { conn.close(); } catch (Exception ignored) { }
        if (holder != null) {
            try { holder.getClass().getMethod("close").invoke(holder); } catch (Exception ignored) { }
        }
        System.exit(0);
    }

    /** Render a JDBC value so type surprises (byte[] for H2 JSON) are visible, not hidden. */
    static String render(Object o) {
        if (o == null) return "<null>";
        if (o instanceof byte[] b) return "byte[]:" + new String(b);
        if (o instanceof Array a) {
            try { return "Array:" + Arrays.deepToString((Object[]) a.getArray()); }
            catch (Exception e) { return "Array:?"; }
        }
        return o.getClass().getSimpleName() + ":" + o;
    }

    static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("  +", " ").trim();
        return s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }
}
