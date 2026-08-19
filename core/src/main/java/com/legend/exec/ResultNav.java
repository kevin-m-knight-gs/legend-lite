// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.spec.CatalogGrids;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedFold;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE {@code ResultSet}-METAMODEL NAVIGATION LOWERING (One-Platform
 * Plan Phase 1). The engine's own spec
 * ({@code platform_store_relational/functions.pure}) says
 * {@code executeInDb} returns the CLASS
 * {@code ResultSet { columnNames: String[*], rows: Row[*] }}, where
 * {@code Row.value(name)} is defined as
 * {@code at($this.values, indexOf($this.parent.columnNames, $name))}.
 * This class is the platform's lowering rule for that navigation: a
 * typed chain over a grid translates to ORDINARY MIR — a
 * {@link SqlSelect} over the ONE {@link SqlSource.RawSql} seam — and
 * executes through the standard {@link Executor}, which owns shaping,
 * carriers, and the egress rules. No SQL strings, no private carrier,
 * no hand shaping (all three died with GridReads, this class's
 * predecessor; the shape RECOGNITION below survives as the documented
 * navigation-to-relation mapping).
 *
 * <p>Column NAMES are plan-time SCHEMA FACTS (the LIMIT-0 metadata
 * probe; catalog grids know their projections statically) and are
 * served as constants — the same class of fact as a table's columns.
 *
 * <p>RawSql QUARANTINE: the one {@code new SqlSource.RawSql} below is
 * the chartered construction site (RawSqlLedgerTest register +
 * ArchitectureTest bytecode rule). The text it carries is AUTHORED —
 * the corpus's {@code executeInDb} argument (boundary-adapted, the R0
 * contract) or the registered catalog SQL — never platform-composed.
 */
public final class ResultNav {

    private ResultNav() {
    }

    /** The recognized chain lowered to MIR and EXECUTED; null = not a
     * grid navigation (the caller falls through to the next channel). */
    public static @com.legend.Nullable ExecutionResult tryExec(
            TypedSpec root, Map<String, TypedSpec> lets, ModelContext ctx,
            Connection conn, com.legend.sql.dialect.SqlDialect dialect)
            throws SQLException {
        TypedSpec n = resolve(root, lets);
        // toString / toOne wrappers peel off the root inward
        boolean asString = false;
        while (n instanceof TypedNativeCall nc && nc.args().size() == 1) {
            String fqn = nc.callee().qualifiedName();
            if ("meta::pure::functions::string::toString".equals(fqn)) {
                asString = true;
            } else if (!"meta::pure::functions::multiplicity::toOne"
                    .equals(fqn)) {
                break;
            }
            n = resolve(nc.args().get(0), lets);
        }
        // at(X, k)
        Long at = null;
        if (n instanceof TypedNativeCall nc && nc.args().size() == 2
                && "meta::pure::functions::collection::at"
                        .equals(nc.callee().qualifiedName())
                && nc.args().get(1) instanceof TypedCInteger k) {
            at = k.value().longValue();
            n = resolve(nc.args().get(0), lets);
        }
        Chain c = chain(n, lets, ctx, conn, dialect);
        if (c == null) {
            return null;
        }
        return switch (c.kind()) {
            case COLUMN_NAMES -> {
                // schema facts, never values: served as constants
                if (at != null) {
                    yield scalar(root, c.names().get(at.intValue()));
                }
                yield new ExecutionResult.Collection(
                        new ArrayList<>(c.names()), root.info().type());
            }
            case COLUMN_VALUES -> {
                String col = c.column();
                if (col == null) {
                    yield null;
                }
                if (c.baseSql() == null) {
                    yield new ExecutionResult.Collection(List.of(),
                            root.info().type());
                }
                if (c.row() != null) {
                    // rows->at(k).value('NAME'): one cell
                    yield run(select(c, col, asString)
                                    .withLimit(1L).withOffset(c.row()),
                            root.info(), conn, dialect);
                }
                if (at != null) {
                    yield run(select(c, col, asString)
                                    .withLimit(1L).withOffset(at),
                            root.info(), conn, dialect);
                }
                yield run(select(c, col, asString), root.info(), conn,
                        dialect);
            }
            case CELLS -> {
                if (asString && at == null) {
                    yield null;   // a peel over the whole stream: wall
                }
                if (at == null) {
                    // the whole cell stream: every value DB-produced
                    // and egressed through the ONE Executor leaf path;
                    // Java reshapes 2D to row-major 1D (decode-class)
                    if (c.baseSql() == null) {
                        yield new ExecutionResult.Collection(List.of(),
                                root.info().type());
                    }
                    ExecutionResult.Tabular grid =
                            (ExecutionResult.Tabular) run(
                                    SqlSelect.starOf(source(c)),
                                    gridInfo(c), conn, dialect);
                    List<Object> flat = new ArrayList<>();
                    for (Row r : grid.rows()) {
                        for (int i = 0; i < c.names().size(); i++) {
                            flat.add(r.get(i));
                        }
                    }
                    yield new ExecutionResult.Collection(flat,
                            root.info().type());
                }
                int ncols = c.names().size();
                String col = c.names().get((int) (at % ncols));
                yield run(select(c, col, asString)
                                .withLimit(1L).withOffset(at / ncols),
                        root.info(), conn, dialect);
            }
            case ROWS -> {
                // bare rows are REAL rows (user question 2026-08-18:
                // "don't we execute and get something back?" — yes, and
                // the platform's own Row carrier is the honest result):
                // the grid executes TABULAR through the one Executor
                // leaf path and the collection holds the actual Row
                // objects with their real cells. Positional reads went
                // through at(); a peel over rows walls.
                if (at != null || c.row() != null || asString) {
                    yield null;
                }
                if (c.baseSql() == null) {
                    yield new ExecutionResult.Collection(List.of(),
                            root.info().type());
                }
                ExecutionResult.Tabular grid =
                        (ExecutionResult.Tabular) run(
                                SqlSelect.starOf(source(c)),
                                gridInfo(c), conn, dialect);
                yield new ExecutionResult.Collection(
                        new ArrayList<>(grid.rows()), root.info().type());
            }
        };
    }

    // ===== MIR construction + execution (the part that replaced =====
    // ===== GridReads' string SQL, HostResultSet, and hand shaping) ===

    private static SqlSource source(Chain c) {
        List<OutputCol> outs = new ArrayList<>(c.names().size());
        for (String name : c.names()) {
            outs.add(new OutputCol(name, SqlType.Scalar.VARCHAR, true));
        }
        return new SqlSource.RawSql(
                java.util.Objects.requireNonNull(c.baseSql()), "_g", outs);
    }

    private static SqlSelect select(Chain c, String col, boolean asString) {
        SqlExpr e = new SqlExpr.Column(null, col);
        if (asString) {
            // toString rides the PROJECTION — the DATABASE renders the
            // text; NULL stays NULL, the pure EMPTY (Tier-1 finding B)
            e = new SqlExpr.Cast(e, SqlType.Scalar.VARCHAR);
        }
        return SqlSelect.starOf(source(c)).withProjections(
                List.of(new SqlSelect.Projection(e, "v")),
                List.of(new OutputCol("v", SqlType.Scalar.VARCHAR, true)));
    }

    private static ExecutionResult run(SqlSelect q, ExprType rootInfo,
            Connection conn, com.legend.sql.dialect.SqlDialect dialect)
            throws SQLException {
        // shape EXPLICIT: a grid cell's Any-typed root is a VALUE read
        // (scalar/collection by multiplicity) — the type alone would
        // classify a bare class type GRAPH (ResultShape's own caveat)
        ResultShape shape = rootInfo.type() instanceof Type.RelationType
                ? ResultShape.TABULAR
                : rootInfo.multiplicity().isMany()
                        ? ResultShape.COLLECTION : ResultShape.SCALAR;
        return Executor.execute(dialect.render(q), q, rootInfo, shape,
                conn, dialect);
    }

    /** The synthetic relation type of the whole grid (cells stream):
     * every column Any[0..1] — the probe knows names, never types. */
    private static ExprType gridInfo(Chain c) {
        List<Type.Column> cols = new ArrayList<>(c.names().size());
        for (String name : c.names()) {
            cols.add(new Type.Column(name,
                    new Type.ClassType(PlatformTypes.ANY),
                    new Multiplicity.Bounded(0, 1)));
        }
        return ExprType.one(new Type.RelationType(cols, List.of()));
    }

    private static ExecutionResult scalar(TypedSpec root, Object v) {
        return new ExecutionResult.Scalar(v, root.info().type());
    }

    /** LIMIT-0 metadata probe: the projection NAMES of a raw read —
     * schema only, never a value (the E1 probe discipline). Even the
     * probe is MIR-rendered — this class composes zero SQL text. */
    /** Unchecked form for the splice-hook lambda (the probe is a schema
     * read; a failure there is a genuine setup fault, surfaced loudly). */
    public static List<String> probeNamesUnchecked(String sql,
            Connection conn, com.legend.sql.dialect.SqlDialect dialect) {
        try {
            return probeNames(sql, conn, dialect);
        } catch (SQLException e) {
            throw new IllegalStateException("schema probe failed: " + sql, e);
        }
    }

    public static List<String> probeNames(String sql, Connection conn,
            com.legend.sql.dialect.SqlDialect dialect) throws SQLException {
        SqlSelect probe = SqlSelect.starOf(
                new SqlSource.RawSql(sql, "_p", List.of()))
                .withLimit(0L);
        try (var st = conn.createStatement();
                var rs = st.executeQuery(dialect.render(probe))) {
            var md = rs.getMetaData();
            List<String> names = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                names.add(md.getColumnLabel(i));
            }
            return names;
        }
    }

    // ===== the navigation-to-relation MAPPING (the recognition =====
    // ===== rules — corpus-proven, carried over from GridReads) ======

    private enum Kind { ROWS, COLUMN_NAMES, COLUMN_VALUES, CELLS }

    /** A recognized grid chain: the AUTHORED base SQL (null = empty
     * grid), its projection names, what the chain reads, and an
     * optional single-ROW index ({@code rows->at(k)}). */
    private record Chain(Kind kind, @com.legend.Nullable String baseSql,
            List<String> names, @com.legend.Nullable String column,
            @com.legend.Nullable Long row) {
        Chain(Kind kind, @com.legend.Nullable String baseSql,
                List<String> names, @com.legend.Nullable String column) {
            this(kind, baseSql, names, column, null);
        }
    }

    private static @com.legend.Nullable Chain chain(TypedSpec n,
            Map<String, TypedSpec> lets, ModelContext ctx,
            Connection conn, com.legend.sql.dialect.SqlDialect dialect)
            throws SQLException {
        n = resolve(n, lets);
        // the INLINED Row.value body (the G-half inliner unfolds the
        // engine's qualified property): at($row.values,
        // indexOf($row.parent.columnNames, 'NAME')) — a by-NAME cell
        if (n instanceof TypedNativeCall vc && vc.args().size() == 2
                && "meta::pure::functions::collection::at"
                        .equals(vc.callee().qualifiedName())
                && resolve(vc.args().get(1), lets)
                        instanceof TypedNativeCall idx
                && "meta::pure::functions::collection::indexOf"
                        .equals(idx.callee().qualifiedName())
                && idx.args().size() == 2
                && resolve(idx.args().get(1), lets)
                        instanceof TypedCString colName) {
            Chain cells = chain(vc.args().get(0), lets, ctx, conn, dialect);
            Chain namesOf = chain(namesBottom(idx.args().get(0), lets),
                    lets, ctx, conn, dialect);
            if (cells != null && cells.kind() == Kind.CELLS
                    && cells.row() != null && namesOf != null
                    && java.util.Objects.equals(namesOf.baseSql(),
                            cells.baseSql())
                    && cells.names().contains(colName.value())) {
                return new Chain(Kind.COLUMN_VALUES, cells.baseSql(),
                        cells.names(), colName.value(), cells.row());
            }
            return null;
        }
        // rows->at(k): a single-ROW selection over the grid
        if (n instanceof TypedNativeCall atc && atc.args().size() == 2
                && "meta::pure::functions::collection::at"
                        .equals(atc.callee().qualifiedName())
                && atc.args().get(1) instanceof TypedCInteger rk) {
            Chain src = chain(atc.args().get(0), lets, ctx, conn, dialect);
            if (src != null && src.kind() == Kind.ROWS && src.row() == null) {
                return new Chain(Kind.ROWS, src.baseSql(), src.names(),
                        null, rk.value().longValue());
            }
            return null;
        }
        // fold({a,b| concatenate($a.values->at(k), $b)}, []) over rows:
        // the column-collect idiom — column k of the grid
        if (n instanceof TypedFold f) {
            Chain src = chain(f.source(), lets, ctx, conn, dialect);
            if (src == null || src.kind() != Kind.ROWS) {
                return null;
            }
            Integer k = foldCollectColumn(f.reducer());
            if (k == null || !emptyInit(f.init())) {
                return null;
            }
            return new Chain(Kind.COLUMN_VALUES, src.baseSql(), src.names(),
                    src.names().get(k));
        }
        // Row.value('NAME') over rows — direct or AUTO-MAPPED (a many
        // source wraps the qualified-property call in a TypedMap whose
        // mapper body is value($row, 'NAME'))
        if (n instanceof TypedUserCall uc) {
            Chain c = valueRead(uc, uc.args().isEmpty() ? null
                    : uc.args().get(0), null, lets, ctx, conn, dialect);
            if (c != null) {
                return c;
            }
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedMap m
                && m.mapper().parameters().size() == 1
                && !m.mapper().body().isEmpty()) {
            TypedSpec body = m.mapper().body().get(m.mapper().body().size() - 1);
            String rowVar = m.mapper().parameters().get(0);
            if (body instanceof TypedUserCall muc) {
                Chain c = valueRead(muc, m.source(), rowVar, lets, ctx, conn, dialect);
                if (c != null) {
                    return c;
                }
            }
            // .values over rows[*] AUTO-MAPS too: map({r|$r.values}) —
            // the flattened row-major cell stream
            if (body instanceof TypedPropertyAccess bp
                    && "values".equals(bp.property())
                    && bp.source() instanceof TypedVariable brv
                    && brv.name().equals(rowVar)) {
                Chain src = chain(m.source(), lets, ctx, conn, dialect);
                if (src != null && src.kind() == Kind.ROWS) {
                    return new Chain(Kind.CELLS, src.baseSql(), src.names(),
                            null);
                }
            }
        }
        if (n instanceof TypedPropertyAccess pa) {
            Chain src = chain(pa.source(), lets, ctx, conn, dialect);
            if (src == null) {
                return null;
            }
            return switch (pa.property()) {
                // .rows over the grid: the grid IS the row source
                case "rows" -> src.kind() == Kind.ROWS
                        && src.column() == null ? src : null;
                case "parent" -> src.kind() == Kind.ROWS
                        ? new Chain(Kind.ROWS, src.baseSql(), src.names(),
                                null)
                        : null;
                case "columnNames" -> src.kind() == Kind.ROWS
                        ? new Chain(Kind.COLUMN_NAMES, src.baseSql(),
                                src.names(), null)
                        : null;
                case "values" -> src.kind() == Kind.ROWS
                        ? new Chain(Kind.CELLS, src.baseSql(), src.names(),
                                null, src.row())
                        : null;
                default -> null;
            };
        }
        // the TYPED grid bottom (Phase 1c): the Typer types a literal
        // single-READ executeInDb as its relation directly — the chain
        // grammar is unchanged, only the leaf spelling moved
        if (n instanceof com.legend.compiler.spec.typed.TypedRawSqlRelation r) {
            return new Chain(Kind.ROWS, r.sql(),
                    probeNames(r.sql(), conn, dialect), null);
        }
        // the grid bottom: a fetchDb* call with literal patterns
        if (n instanceof TypedNativeCall nc
                && PlatformTypes.isFetchDbFn(nc.callee().qualifiedName())) {
            return grid(nc, lets, ctx);
        }
        // the executeInDb READ bottom: AUTHORED SQL text over the
        // AMBIENT session (F6.6), boundary-adapted like the write path;
        // projection names come from a LIMIT-0 metadata probe at
        // execution (schema read, never value sniffing — E1 precedent)
        if (n instanceof TypedNativeCall nc
                && PlatformTypes.EXECUTE_IN_DB
                        .equals(nc.callee().qualifiedName())
                && resolve(nc.args().get(0), lets)
                        instanceof TypedCString sql) {
            String raw = sql.value().strip();
            if (raw.endsWith(";")) {
                raw = raw.substring(0, raw.length() - 1);
            }
            // the text stays AUTHORED — the dialect's RawSqlAdapt pass
            // owns the boundary translation at render (slice 3: the
            // slice-1 pre-adaptation double-translated under the pass)
            return new Chain(Kind.ROWS, raw,
                    probeNames(raw, conn, dialect), null);
        }
        return null;
    }

    /** {@code value($row, 'NAME')} — the engine Row qualified property
     * (owner package + name): direct form reads {@code src} from the
     * call's first arg; auto-mapped form receives the map SOURCE and
     * requires the call's first arg to be the mapper's row variable. */
    private static @com.legend.Nullable Chain valueRead(TypedUserCall uc,
            @com.legend.Nullable TypedSpec src,
            @com.legend.Nullable String rowVar,
            Map<String, TypedSpec> lets, ModelContext ctx,
            Connection conn, com.legend.sql.dialect.SqlDialect dialect)
            throws SQLException {
        if (src == null || uc.args().size() != 2
                || !uc.callee().qualifiedName().contains("::execute::")
                || !uc.callee().qualifiedName().endsWith("value")
                || !(uc.args().get(1) instanceof TypedCString col)) {
            return null;
        }
        if (rowVar != null
                && !(uc.args().get(0) instanceof TypedVariable rv
                        && rv.name().equals(rowVar))) {
            return null;
        }
        Chain rows = chain(src, lets, ctx, conn, dialect);
        if (rows == null || rows.kind() != Kind.ROWS
                || !rows.names().contains(col.value())) {
            return null;
        }
        return new Chain(Kind.COLUMN_VALUES, rows.baseSql(), rows.names(),
                col.value(), rows.row());
    }

    /** The catalog grid's SQL text for a fetchDb* call with literal
     * patterns (null = not compile-time recognizable, or the empty
     * no-facts PK grid). */
    public static @com.legend.Nullable String gridSql(TypedNativeCall nc,
            Map<String, TypedSpec> lets, ModelContext ctx) {
        Chain c = grid(nc, lets, ctx);
        return c == null ? null : c.baseSql();
    }

    private static @com.legend.Nullable Chain grid(TypedNativeCall nc,
            Map<String, TypedSpec> lets, ModelContext ctx) {
        String fqn = nc.callee().qualifiedName();
        String a1 = literalPattern(nc, 1, lets);
        String a2 = nc.args().size() > 2 ? literalPattern(nc, 2, lets) : null;
        String a3 = nc.args().size() > 3 ? literalPattern(nc, 3, lets) : null;
        if (bad(a1) || bad(a2) || bad(a3)) {
            return null;    // a non-literal pattern is not a grid read
        }
        PlatformTypes.FetchDbKind kind = PlatformTypes.fetchDbKind(fqn);
        List<String> names = CatalogGrids.gridColumns(kind);
        String sql = switch (kind) {
            case SCHEMAS -> CatalogGrids.fetchSql(fqn, a1, null, null);
            case TABLES -> CatalogGrids.fetchSql(fqn, a1, a2, null);
            case COLUMNS -> CatalogGrids.fetchSql(fqn, a1, a2, a3);
            case PRIMARY_KEYS -> CatalogGrids.pkSql(
                    CatalogGrids.pkFacts(ctx, nc.args().get(0), lets),
                    a1, a2);
        };
        return new Chain(Kind.ROWS, sql, names, null);
    }

    /** Sentinel-based literal pattern: null = empty ([]), the string =
     * a literal, {@link #BAD} = not compile-time recognizable. */
    private static final String BAD = " bad";

    private static boolean bad(@com.legend.Nullable String s) {
        return BAD.equals(s);
    }

    private static @com.legend.Nullable String literalPattern(
            TypedNativeCall nc, int i, Map<String, TypedSpec> lets) {
        TypedSpec a = resolve(nc.args().get(i), lets);
        if (a instanceof TypedCString cs) {
            return cs.value();
        }
        if (a instanceof TypedCollection col && col.elements().isEmpty()) {
            return null;
        }
        return BAD;
    }

    /** {@code fold({a,b| concatenate($a.values->at(k), $b)}, ...)} —
     * the column-collect reducer; returns k or null. */
    private static @com.legend.Nullable Integer foldCollectColumn(
            TypedLambda reducer) {
        if (reducer.parameters().size() != 2 || reducer.body().isEmpty()) {
            return null;
        }
        TypedSpec body = reducer.body().get(reducer.body().size() - 1);
        if (!(body instanceof TypedNativeCall cc)
                || !"meta::pure::functions::collection::concatenate"
                        .equals(cc.callee().qualifiedName())
                || cc.args().size() != 2) {
            return null;
        }
        String acc = reducer.parameters().get(1);
        if (!(cc.args().get(1) instanceof TypedVariable av)
                || !av.name().equals(acc)) {
            return null;
        }
        if (!(cc.args().get(0) instanceof TypedNativeCall at)
                || !"meta::pure::functions::collection::at"
                        .equals(at.callee().qualifiedName())
                || at.args().size() != 2
                || !(at.args().get(1) instanceof TypedCInteger k)) {
            return null;
        }
        String row = reducer.parameters().get(0);
        if (!(at.args().get(0) instanceof TypedPropertyAccess pv)
                || !"values".equals(pv.property())
                || !(pv.source() instanceof TypedVariable rv)
                || !rv.name().equals(row)) {
            return null;
        }
        return (int) (long) k.value();
    }

    private static boolean emptyInit(TypedSpec init) {
        return init instanceof TypedCollection c && c.elements().isEmpty();
    }

    /** The source under a {@code .columnNames} access (the names side
     * of the inlined Row.value body). */
    private static TypedSpec namesBottom(TypedSpec n,
            Map<String, TypedSpec> lets) {
        n = resolve(n, lets);
        return n instanceof TypedPropertyAccess pa
                && "columnNames".equals(pa.property()) ? pa.source() : n;
    }

    /** The GRID channel's admission predicate (HostEval's fold-in,
     * Phase 1 batch 2): a chain whose PRIMARY SOURCE bottoms at
     * executeInDb, or any fetchDb containment (its only corpus shapes
     * are grid reads). Routing on containment for executeInDb
     * collapsed the sweep once (2096->408) — bottoming is the pin. */
    public static boolean owns(TypedSpec root, Map<String, TypedSpec> lets) {
        TypedSpec bottom = chainBottom(root, lets);
        if (bottom instanceof
                com.legend.compiler.spec.typed.TypedRawSqlRelation) {
            return true;   // the Phase 1c typed grid leaf
        }
        if (bottom instanceof TypedNativeCall b
                && PlatformTypes.EXECUTE_IN_DB
                        .equals(b.callee().qualifiedName())) {
            return true;
        }
        return containsFetchDb(root);
    }

    private static boolean containsFetchDb(TypedSpec root) {
        if (root instanceof TypedNativeCall nc
                && PlatformTypes.isFetchDbFn(nc.callee().qualifiedName())) {
            return true;
        }
        for (TypedSpec c : root.children()) {
            if (containsFetchDb(c)) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<String> READ_CHAIN_FNS =
            java.util.Set.of(
                    "meta::pure::functions::collection::fold",
                    "meta::pure::functions::collection::map",
                    "meta::pure::functions::collection::concatenate",
                    "meta::pure::functions::collection::at",
                    "meta::pure::functions::collection::first",
                    "meta::pure::functions::collection::size",
                    "meta::pure::functions::collection::indexOf",
                    "meta::pure::functions::multiplicity::toOne",
                    "meta::pure::functions::string::toString");

    /** Walk the primary source chain (property access sources, fold/map
     * sources, READ-shaped collection-native first args, user-call and
     * match receivers, let-bound variables) to the expression's root —
     * shared with {@link StoreNav#owns}. */
    static TypedSpec chainBottom(TypedSpec n, Map<String, TypedSpec> lets) {
        while (true) {
            switch (n) {
                case TypedPropertyAccess pa -> n = pa.source();
                case TypedFold f -> n = f.source();
                case com.legend.compiler.spec.typed.TypedMap m ->
                        n = m.source();
                case TypedUserCall uc -> {
                    if (uc.args().isEmpty()) {
                        return uc;
                    }
                    n = uc.args().get(0);
                }
                case com.legend.compiler.spec.typed.TypedMatchRuntime mr ->
                        n = mr.input();
                case com.legend.compiler.spec.typed.TypedCast tc ->
                        n = tc.source();
                case com.legend.compiler.spec.typed.TypedLet l ->
                        n = l.value();
                case TypedVariable v -> {
                    TypedSpec bound = lets.get(v.name());
                    if (bound == null) {
                        return v;
                    }
                    n = bound;
                }
                case com.legend.compiler.spec.typed.TypedRawSqlRelation r -> {
                    return r;   // the Phase 1c typed grid leaf
                }
                case TypedNativeCall nc -> {
                    String fqn = nc.callee().qualifiedName();
                    if (PlatformTypes.EXECUTE_IN_DB.equals(fqn)
                            || PlatformTypes.isFetchDbFn(fqn)
                            || PlatformTypes.isStoreNavFn(fqn)) {
                        return nc;
                    }
                    if (nc.args().isEmpty()
                            || !READ_CHAIN_FNS.contains(fqn)) {
                        return nc;
                    }
                    n = nc.args().get(0);
                }
                default -> {
                    return n;
                }
            }
        }
    }

    private static TypedSpec resolve(TypedSpec n,
            Map<String, TypedSpec> lets) {
        int guard = 0;
        while (n instanceof TypedVariable v && guard++ < 32) {
            TypedSpec bound = lets.get(v.name());
            if (bound == null) {
                return n;
            }
            n = bound;
        }
        return n;
    }
}
