package com.legend.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legend.builtin.DynaFn;
import com.legend.builtin.Pure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DynaFn} is GENERATED from the pinned legend-engine checkout's SQL
 * rendering registries and VERIFIED here: every {@code dynaFnToSql('name', …)}
 * in {@code extensionDefaults.pure} and the dialect extensions is a member
 * (and only those), each member carries exactly the dialects that register it,
 * and each member's resolution holds — a PURE name resolves in the catalog, a
 * SHIM names a Lite constant, TRANSLATED names have arms (and armed names are TRANSLATED or PURE),
 * UNSUPPORTED only shrinks. {@code -Ddynafn.generate=1} rewrites the members
 * (existing resolutions kept; a new engine name lands UNSUPPORTED).
 */
class DynaFnRegistryTest {

    private static final Pattern DYNA = Pattern.compile("dynaFnToSql\\('([A-Za-z0-9_]+)'");
    /** Shrink-only: engine operators the platform handles by nothing yet. */
    static final int UNSUPPORTED_MAX = 35;

    static Path engineRoot() {
        return Path.of(System.getProperty("legend.engine.root",
                System.getProperty("user.home") + "/legend/legend-engine"));
    }

    static String dialectOf(Path p) {
        String s = p.toString().replace('\\', '/');
        Matcher m = Pattern.compile("core_relational_(\\w+)/").matcher(s);
        if (m.find()) {
            return m.group(1).toUpperCase();
        }
        m = Pattern.compile("dbSpecific/(\\w+)/").matcher(s);
        if (m.find()) {
            return m.group(1).toUpperCase();
        }
        return s.endsWith("extensionDefaults.pure") ? "DEFAULT" : "OTHER";
    }

    /** name → dialects, read from every registry file in the checkout. */
    static TreeMap<String, TreeSet<String>> upstream() throws IOException {
        TreeMap<String, TreeSet<String>> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(engineRoot())) {
            for (Path p : walk.filter(x -> x.toString().endsWith(".pure")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (!text.contains("dynaFnToSql(")) {
                    continue;
                }
                String dialect = dialectOf(p);
                Matcher m = DYNA.matcher(text);
                while (m.find()) {
                    out.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(dialect);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("the registry IS the engine's: every dynaFnToSql name, with its dialects, and nothing else")
    void registryMatchesTheCheckout() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(engineRoot()), "legend-engine checkout not present");
        TreeMap<String, TreeSet<String>> up = upstream();
        if ("1".equals(System.getProperty("dynafn.generate"))) {
            generate(up);
            return;
        }
        TreeMap<String, TreeSet<String>> ours = new TreeMap<>();
        for (DynaFn d : DynaFn.values()) {
            TreeSet<String> ds = new TreeSet<>();
            d.dialects().forEach(x -> ds.add(x.name()));
            ours.put(d.dynaName(), ds);
        }
        assertEquals(up, ours, "DynaFn drifted from the checkout's registries — regenerate with -Ddynafn.generate=1");
    }

    @Test
    @DisplayName("every resolution holds: PURE in the catalog, SHIM a Lite constant, TRANSLATED armed, UNSUPPORTED shrink-only")
    void resolutionsHold() {
        List<String> bad = new ArrayList<>();
        for (DynaFn d : DynaFn.values()) {
            switch (d.resolution()) {
                case PURE -> {
                    if (Pure.nativeFunctionsAt(d.dynaName()).isEmpty()) {
                        bad.add(d.dynaName() + ": PURE but no catalog native of that name");
                    }
                }
                case SHIM -> {
                    String fqn = d.liteFqn();
                    if (!fqn.startsWith(Pure.Lite.PKG) || Pure.nativeFunctionsAt(fqn).isEmpty()) {
                        bad.add(d.dynaName() + ": SHIM but " + fqn + " is not a registered Lite native");
                    }
                }
                case TRANSLATED, UNSUPPORTED -> { }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
        java.util.Set<DynaFn> arms = com.legend.normalizer.DynaFnArms.ARMS;
        for (DynaFn d : DynaFn.withResolution(DynaFn.Resolution.TRANSLATED)) {
            assertTrue(arms.contains(d), d.dynaName() + ": TRANSLATED but the translator declares no arm for it");
        }
        for (DynaFn d : arms) {
            assertTrue(d.resolution() == DynaFn.Resolution.TRANSLATED || d.resolution() == DynaFn.Resolution.PURE,
                    d.dynaName() + ": has a translator arm but is " + d.resolution()
                    + " — an armed name is TRANSLATED (nothing passes through) or PURE (pure's own shape passes through)");
        }
        int unsupported = DynaFn.withResolution(DynaFn.Resolution.UNSUPPORTED).size();
        assertTrue(unsupported <= UNSUPPORTED_MAX, "UNSUPPORTED dynafunctions grew: " + unsupported + " > " + UNSUPPORTED_MAX);
        assertTrue(unsupported == UNSUPPORTED_MAX, "UNSUPPORTED shrank to " + unsupported + " — re-pin UNSUPPORTED_MAX (headroom is not a pin)");
    }

    /** Rewrite the member block of DynaFn.java from the checkout, keeping each
     *  existing member's resolution and Lite constant. */
    private static void generate(TreeMap<String, TreeSet<String>> up) throws IOException {
        Path src = Path.of("src/main/java/com/legend/builtin/DynaFn.java");
        String text = Files.readString(src, StandardCharsets.UTF_8);
        Map<String, String[]> existing = new TreeMap<>();
        Matcher m = Pattern.compile("^    ([A-Z_0-9]+)\\(\"(\\w+)\", Resolution\\.(\\w+), (null|Pure\\.Lite\\.\\w+), ", Pattern.MULTILINE).matcher(text);
        while (m.find()) {
            existing.put(m.group(2), new String[] {m.group(3), m.group(4)});
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> e : up.entrySet()) {
            String[] keep = existing.getOrDefault(e.getKey(), new String[] {"UNSUPPORTED", "null"});
            String member = e.getKey().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
            StringBuilder ds = new StringBuilder();
            for (String d : e.getValue()) {
                ds.append(ds.length() == 0 ? "" : ", ").append("Dialect.").append(d);
            }
            lines.add("    " + member + "(\"" + e.getKey() + "\", Resolution." + keep[0] + ", " + keep[1] + ", " + ds + "),");
        }
        String last = lines.get(lines.size() - 1);
        lines.set(lines.size() - 1, last.substring(0, last.length() - 1) + ";");
        int start = text.indexOf("public enum DynaFn {\n") + "public enum DynaFn {\n".length();
        Matcher end = Pattern.compile("^    [A-Z_0-9]+\\(.*\\);\\n", Pattern.MULTILINE).matcher(text);
        if (!end.find(start)) {
            throw new IllegalStateException("member block not found");
        }
        Files.writeString(src, text.substring(0, start) + String.join("\n", lines) + "\n" + text.substring(end.end()), StandardCharsets.UTF_8);
        System.out.println("[dynafn] regenerated " + lines.size() + " members");
    }
}
