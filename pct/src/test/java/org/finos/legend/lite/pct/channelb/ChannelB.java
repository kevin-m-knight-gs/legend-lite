// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct.channelb;

import com.legend.Compiler;
import com.legend.compiler.NameResolver;
import com.legend.compiler.element.ModelContext;
import com.legend.model.FunctionDefinition;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * PCT CHANNEL B (One-Platform Plan Phase 4): OUR parser compiles the
 * {@code PCT.test} functions, OUR platform executes them — no
 * reference-interpreter object ever appears. The adapter is the
 * IDENTITY: {@code $f->eval(|expr)} β-reduces to {@code expr}, because
 * this platform IS the executor; the whole test body then compiles and
 * runs as ordinary pure — asserts included, which lower to
 * {@code CASE WHEN cond THEN TRUE ELSE error(msg)} and run IN THE
 * DATABASE (tenet #1).
 *
 * <p>Outcomes are three-bucket honest (the plan's addendum #6):
 * {@code PASS}/{@code FAIL} feed the AGREE/WIRE-BUG diff against
 * channel A; {@code DECLINED} names tests whose shape channel B does
 * not run (reference-only reflection, non-identity adapter spellings) —
 * a reason, never a silent skip.
 */
public final class ChannelB {

    private ChannelB() {
    }

    public record Outcome(String testFqn, Status status, String detail) {
    }

    public enum Status { PASS, FAIL, DECLINED, ERROR }

    /** Run every {@code <<PCT.test>>} function found under the scope
     * directories. The MODEL is the whole {@code modelRoot} tree (the
     * platform sources — shared test models, the assert family, the
     * function definitions the tests exercise; files that do not parse
     * are collected as walls, never silently dropped); DISCOVERY is
     * scoped. One in-memory DuckDB session per test (isolation — PCT
     * tests assume a fresh world). */
    public static List<Outcome> run(Path modelRoot, List<Path> scopeDirs)
            throws IOException {
        return run(modelRoot, scopeDirs, new ArrayList<>());
    }

    public static List<Outcome> run(Path modelRoot, List<Path> scopeDirs,
            List<String> wallsOut) throws IOException {
        List<Compiler.ModelSource> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(modelRoot)) {
            for (Path f : walk.filter(p -> p.toString().endsWith(".pure"))
                    .toList()) {
                sources.add(new Compiler.ModelSource(
                        modelRoot.relativize(f).toString(),
                        Files.readString(f)));
            }
        }
        List<String> parseWalls = wallsOut;
        // ITERATIVE WALL COLLECTION (census-first): the platform tree
        // includes m3-metamodel internals our model does not carry; an
        // integrity failure names its element — drop that element's
        // SOURCE FILE (never the element alone: half-files lie) and
        // recompile. Every drop is recorded; the loop is bounded.
        Compiler.ParsedModule module = null;
        ModelContext ctx = null;
        List<String> modelWalls = new ArrayList<>();
        for (int round = 0; round < 200; round++) {
            parseWalls.clear();
            parseWalls.addAll(modelWalls);
            module = Compiler.parseSources(sources,
                    (name, msg) -> parseWalls.add(name + ": " + msg),
                    com.legend.parser.Dialect.LEGEND_PLATFORM);
            try {
                // TENET #2 / platform-owned doctrine: a parsed NATIVE
                // declaration is the SPEC of a function the platform
                // registry already defines — the registry is the
                // definition; the parsed twin drops (keeping it made
                // every native an ambiguous 2-candidate tie).
                var kept = module.model().elements().stream()
                        .filter(e -> !(e instanceof
                                com.legend.model.NativeFunctionDefinition))
                        .toList();
                var pruned = new com.legend.model.ParsedModel(kept,
                        module.model().imports(),
                        module.model().source(),
                        module.model().elementOffsets(),
                        module.model().elementImports(),
                        module.model().elementSources(),
                        module.model().unclaimedSections());
                ctx = Compiler.buildModel(pruned);
                break;
            } catch (com.legend.error.ModelException e) {
                String el = e.element();
                String src = el == null ? null
                        : module.model().elementSources().get(el);
                if (src == null) {
                    throw e;   // unattributable — loud
                }
                final String drop = src;
                sources.removeIf(s -> s.name().equals(drop));
                modelWalls.add(drop + ": MODEL-WALL " + first(e.getMessage()));
            }
        }
        if (ctx == null) {
            throw new IllegalStateException(
                    "model did not converge after 200 wall rounds");
        }
        List<String> scopePrefixes = scopeDirs.stream()
                .map(d -> modelRoot.relativize(d).toString()).toList();
        List<Outcome> out = new ArrayList<>();
        for (var el : module.model().elements()) {
            if (!(el instanceof FunctionDefinition fd) || !isPctTest(fd)) {
                continue;
            }
            String src = module.model().elementSources()
                    .get(fd.qualifiedName());
            if (src == null || scopePrefixes.stream()
                    .noneMatch(src::startsWith)) {
                continue;
            }
            out.add(runOne(fd, module, ctx));
        }
        return out;
    }

    private static boolean isPctTest(FunctionDefinition fd) {
        return fd.stereotypes() != null && fd.stereotypes().stream()
                .anyMatch(s -> "test".equals(s.stereotypeName())
                        && (s.profileName().equals("PCT")
                                || s.profileName().endsWith("::PCT")));
    }

    private static Outcome runOne(FunctionDefinition fd,
            Compiler.ParsedModule module, ModelContext ctx) {
        String fqn = fd.qualifiedName();
        if (fd.parameters().size() != 1) {
            return new Outcome(fqn, Status.DECLINED,
                    "non-adapter signature (" + fd.parameters().size()
                            + " params)");
        }
        String fParam = fd.parameters().get(0).name();
        List<ValueSpecification> body;
        try {
            body = eliminateAdapter(fd.body(), fParam);
        } catch (Declined d) {
            return new Outcome(fqn, Status.DECLINED, d.getMessage());
        }
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            var lambda = new LambdaFunction(List.of(), body);
            var imports = module.model().elementImports().get(fqn);
            ValueSpecification resolved = imports == null
                    ? NameResolver.resolveQuery(lambda)
                    : NameResolver.resolveQuery(lambda, imports,
                            ctx.elementFqns());
            Compiler.executeResolved(resolved, ctx, null, conn);
            return new Outcome(fqn, Status.PASS, "");
        } catch (java.sql.SQLException e) {
            // an assert failure arrives as the database's error() — a
            // REAL test failure; other SQL errors are failures too (the
            // diff against channel A adjudicates which are wire bugs)
            return new Outcome(fqn, Status.FAIL, first(e.getMessage()));
        } catch (RuntimeException e) {
            // compile/lowering walls — channel B cannot run the shape
            return new Outcome(fqn, Status.ERROR,
                    e.getClass().getSimpleName() + ": " + first(e.getMessage()));
        }
    }

    private static String first(@com.legend.Nullable String msg) {
        String m = String.valueOf(msg);
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }

    private static final class Declined extends RuntimeException {
        Declined(String why) {
            super(why);
        }
    }

    /** THE IDENTITY ADAPTER: {@code $f->eval(|expr)} → {@code expr}.
     * Any OTHER use of the adapter parameter declines with its shape —
     * never silently dropped. */
    static List<ValueSpecification> eliminateAdapter(
            List<ValueSpecification> body, String fParam) {
        List<ValueSpecification> out = new ArrayList<>(body.size());
        for (ValueSpecification stmt : body) {
            out.add(rewrite(stmt, fParam));
        }
        return out;
    }

    private static ValueSpecification rewrite(ValueSpecification n,
            String fParam) {
        if (n instanceof AppliedFunction af
                && simpleName(af.function()).equals("eval")
                && af.parameters().size() == 2
                && af.parameters().get(0) instanceof Variable v
                && v.name().equals(fParam)
                && af.parameters().get(1) instanceof LambdaFunction lam
                && lam.parameters().isEmpty()
                && lam.body().size() == 1) {
            return rewrite(lam.body().get(0), fParam);
        }
        // any surviving reference to the adapter param is a shape we
        // do not run — decline with the spelling
        if (n instanceof Variable v2 && v2.name().equals(fParam)) {
            throw new Declined("adapter parameter used outside"
                    + " $f->eval(|...) — non-identity shape");
        }
        List<ValueSpecification> kids = n.children();
        if (kids.isEmpty()) {
            return n;
        }
        List<ValueSpecification> rewritten = new ArrayList<>(kids.size());
        boolean changed = false;
        for (ValueSpecification k : kids) {
            ValueSpecification r = rewrite(k, fParam);
            changed |= r != k;
            rewritten.add(r);
        }
        return changed ? n.withChildren(rewritten) : n;
    }

    private static String simpleName(String fn) {
        int i = fn.lastIndexOf("::");
        return i < 0 ? fn : fn.substring(i + 2);
    }
}
