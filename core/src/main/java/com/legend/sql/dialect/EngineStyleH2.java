// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlUnion;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The ENGINE's own H2 SQL text &mdash; for byte-exact {@code toSQLString}
 * goldens (transform/fromPure/tests), never for execution. The formatting
 * contract is MATCHED against the engine's output, not invented: one
 * line, lowercase keywords, each SELECT's leftmost source aliased
 * {@code "root"}, every other join source aliased
 * {@code <lowercase-table>_<n>} with a query-global counter (the engine's
 * replaceAliasName pass), source/output aliases double-quoted, physical
 * column names bare, parenthesized ON clauses.
 */
public class EngineStyleH2 extends AnsiSqlRenderer {

    /** The engine connection's {@code quoteIdentifiers} flag: every
     * physical identifier (schema, table, column) renders double-quoted
     * (plan goldens testQuoteIdentifiersFlag*). */
    private final boolean quoteIdentifiers;

    /** The connection's timeZone — a non-default zone wraps DATETIME
     * placeholders in the engine's {@code GMTtoTZ} template
     * (relationalPlanSupportFunctions gate). Null = default. */
    private final @com.legend.Nullable String timeZone;

    public EngineStyleH2() {
        this(false);
    }

    public EngineStyleH2(boolean quoteIdentifiers) {
        this(quoteIdentifiers, null);
    }

    public EngineStyleH2(boolean quoteIdentifiers, @com.legend.Nullable String timeZone) {
        super(Lexicon.ENGINE_STYLE, TypeNames.ANSI, Spellings.DUCKDB);
        this.quoteIdentifiers = quoteIdentifiers;
        this.timeZone = timeZone;
    }

    private String phys(String name) {
        return quoteIdentifiers ? '"' + name + '"' : name;
    }

    private final Map<String, String> renames = new LinkedHashMap<>();
    private final Map<String, SqlSource.Subselect> subselects =
            new LinkedHashMap<>();

    @Override
    public String render(SqlQuery query) {
        renames.clear();
        subselects.clear();
        rootConsumed.clear();
        planQuery(query, new LinkedHashMap<>());
        StringBuilder sb = new StringBuilder();
        query(sb, query, 0);
        return sb.toString();
    }

    // == the engine's alias plan (replaceAliasName parity, audit 19 F2) ==
    // The engine groups aliases BY LOWERCASED TABLE NAME and numbers
    // 0..n-1 within each group in encounter order; each SELECT's leftmost
    // source renders "root" but still CONSUMES its group's next index
    // (self-join goldens: root + persontable_1). A subselect belongs to
    // its FIRST INNER TABLE's group (goldens: account_info_1, never
    // subselect_0), and its interior plans BEFORE the alias itself.

    private void planQuery(SqlQuery q, Map<String, Integer> groups) {
        switch (q) {
            case SqlSelect s -> {
                if (!(s.from() instanceof SqlSource.Dual)) {
                    planSource(s.from(), true, groups);
                }
            }
            case SqlUnion u -> u.branches().forEach(b -> planQuery(b, groups));
        }
    }

    private void planSource(SqlSource src, boolean leftmost,
            Map<String, Integer> groups) {
        switch (src) {
            case SqlSource.Table t -> {
                if (t.alias() != null) {
                    // alias groups take the BARE table name — the engine's
                    // reAliasQuery keys by table, never schema-qualified
                    // (golden "sourceannouncement_0", not
                    // "s.sourceannouncement_0")
                    String group = t.name()
                            .substring(t.name().lastIndexOf('.') + 1)
                            .toLowerCase(Locale.ROOT);
                    if (leftmost) {
                        // reAliasQuery: every 'root' pair in a group
                        // DEDUPES to one — the group spends ONE index on
                        // root no matter how many nested roots it has
                        consumeRootSlot(group, groups);
                        renames.put(t.alias(), "root");
                    } else {
                        renames.put(t.alias(), nextInGroup(group, groups));
                    }
                }
            }
            case SqlSource.Subselect sub -> {
                // a NAMED frame (view-backed target, union frame) groups
                // by its own model identity — orderpnlview_0,
                // unionalias_0 — never the underlying table's group
                String group = sub.frameName() != null
                        ? sub.frameName().toLowerCase(Locale.ROOT)
                        : firstInnerTable(sub.inner());
                // only the DISTINCT materialization keeps the root name
                // (its frame REPLACES the root table); every other frame
                // is group-named — persontable_0, unionalias_0
                boolean rootFrame = leftmost
                        && sub.inner() instanceof SqlSelect ds
                        && ds.distinct();
                if (rootFrame) {
                    consumeRootSlot(group, groups);
                    renames.put(sub.alias(), "root");
                } else {
                    // PRE-ORDER: the alias numbers before its interior
                    // (reAliasQuery traverse pairs the select before its
                    // children — nested union frames count outermost-first)
                    renames.put(sub.alias(), nextInGroup(group, groups));
                }
                planQuery(sub.inner(), groups);
                subselects.put(sub.alias(), sub);
            }
            case SqlSource.Join j -> {
                planSource(j.left(), leftmost, groups);
                planSource(j.right(), false, groups);
            }
            default -> { }
        }
    }

    private static String nextInGroup(String group, Map<String, Integer> groups) {
        int i = groups.merge(group, 1, Integer::sum) - 1;
        return group + "_" + i;
    }

    /** One index per group for ALL its root-named members (reAliasQuery
     * dedupes ('group','root') pairs before numbering). */
    private final Set<String> rootConsumed = new HashSet<>();

    private void consumeRootSlot(String group, Map<String, Integer> groups) {
        if (rootConsumed.add(group)) {
            nextInGroup(group, groups);
        }
    }

    /** The group a subselect alias renames into: its leftmost inner
     * table's lowercased name (the engine's traverse rule). */
    private static String firstInnerTable(SqlQuery q) {
        SqlSource src = q instanceof SqlSelect s ? s.from()
                : q instanceof SqlUnion u && !u.branches().isEmpty()
                        ? (u.branches().get(0) instanceof SqlSelect bs
                                ? bs.from() : null)
                        : null;
        while (src instanceof SqlSource.Join j) {
            src = j.left();
        }
        return switch (src) {
            case SqlSource.Table t -> t.name()
                    .substring(t.name().lastIndexOf('.') + 1)
                    .toLowerCase(Locale.ROOT);
            case SqlSource.Subselect sub -> firstInnerTable(sub.inner());
            case null, default -> "subselect";
        };
    }

    /** varPlaceHolderToString prefix/suffix/replace-map per parameter
     * kind (the engine's optional-parameter templates). */
    /** The {@code varPlaceHolderToString} spelling of an optional
     * parameter; a non-default connection timeZone wraps DATETIME
     * placeholders in {@code GMTtoTZ}. */
    /** The {@code (${optionalVarPlaceHolderOperationSelector(name,
     * equalEnumOperationSelector(fn(name), 'col in (...)', 'col = ...'),
     * '0 = 1')})} spelling for {@code rawColumn = enumParam}; null when
     * the expression is not that shape. */
    private @com.legend.Nullable String enumSelector(SqlExpr e) {
        if (!(e instanceof SqlExpr.Call c)
                || c.fn() != com.legend.sql.SqlFn.EQUAL
                || c.args().size() != 2) {
            return null;
        }
        // the ENUM param may sit on EITHER side
        SqlExpr.PlanParam p =
                c.args().get(1) instanceof SqlExpr.PlanParam p1
                        && p1.enumMapFn() != null ? p1
                : c.args().get(0) instanceof SqlExpr.PlanParam p0
                        && p0.enumMapFn() != null ? p0 : null;
        if (p == null) {
            return null;
        }
        SqlExpr other = c.args().get(p == c.args().get(1) ? 0 : 1);
        // a REQUIRED param against an enum LITERAL is a host-vs-host
        // comparison — plain placeholder equality ('\${yesOrNo}' = 'NO'),
        // no store column to select over
        if (other instanceof SqlExpr.StringLit && !p.optional()) {
            return null;
        }
        // the class-prop side may arrive as the mapping's enum DECODE
        // case-chain — the template compares the RAW source column
        // (toSourceValues; the engine never compares decoded names)
        SqlExpr colExpr = decodeSourceColumn(other);
        // the rendered column sits inside the template's single-quoted
        // args — escape like the selector arm at holder-equality does
        // (C2.1: a quote-bearing spelling must not walk out of the arg)
        String col = expr(colExpr != null ? colExpr : other, 4)
                .replace("'", "\\'");
        String pn = p.name() + (p.optional() ? "![]" : "");
        String fn = p.enumMapFn() + "(" + pn + ")";
        return "(${optionalVarPlaceHolderOperationSelector(" + pn
                + ", equalEnumOperationSelector(" + fn + ", '" + col
                + " in (${" + fn + "})', '" + col + " = ${" + fn
                + "}'), '0 = 1')})";
    }

    /** The ONE source expression a literal-decode case chain reads
     * ({@link com.legend.sql.DecodeShapes#sourceExpr}), or null. */
    private static @com.legend.Nullable SqlExpr decodeSourceColumn(SqlExpr e) {
        return com.legend.sql.DecodeShapes.sourceExpr(e).orElse(null);
    }

    private String holder(SqlExpr.PlanParam p) {
        String inner = p.name() + "![]";
        if (p.kind() == SqlExpr.PlanParam.Kind.DATETIME
                && timeZone != null) {
            inner = "GMTtoTZ( \"[" + timeZone + "]\" " + inner + ")";
        }
        return "${varPlaceHolderToString(" + inner + " "
                + holderArgs(p.kind()) + " \"null\")}";
    }

    protected String holderArgs(SqlExpr.PlanParam.Kind k) {
        return switch (k) {
            case STRING -> "\"\\'\" \"\\'\" {\"\\'\" : \"\\'\\'\"}";
            case DATE -> "\"\\'\" \"\\'\" {}";
            case DATETIME -> "\"TIMESTAMP\\'\" \"\\'\" {}";
            case FLOAT -> "\"CAST(\" \" AS FLOAT)\" {}";
            case BOOLEAN, ENUM, OTHER -> "\"\" \"\" {}";
        };
    }

    private String rename(String alias) {
        return renames.getOrDefault(alias, alias);
    }

    /** Whether the column reads an ALIASED projection of a plain frame —
     * quoted spelling. NAMED frames (view/join frames render bare
     * interiors) and the distinct root materialization stay physical. */
    private boolean quotedFrameRead(SqlExpr.Column c) {
        SqlSource.Subselect sub = subselects.get(c.table());
        if (sub == null) {
            return false;
        }
        // a UNION frame reads its QUOTED output aliases (tds union
        // goldens: "unionalias_0"."lastName") — unlike view frames,
        // whose interiors render bare; holds for the raw union, the
        // re-projection wrapper and the join-isolation select* alike
        if ("unionAlias".equals(sub.frameName())) {
            return sub.inner().outputs() != null && sub.inner().outputs()
                    .stream().anyMatch(o -> c.name().equals(o.name()));
        }
        if (sub.inner() instanceof com.legend.sql.SqlUnion) {
            return false;
        }
        if (sub.frameName() != null
                || !(sub.inner() instanceof SqlSelect is)
                || is.distinct()) {
            return false;
        }
        for (SqlSelect.Projection p : is.projections()) {
            if (c.name().equals(p.alias())) {
                return true;
            }
        }
        return false;
    }

    /** The engine's H2-NEW date spelling has no space ({@code
     * DATE'2005-10-10'} — plan and h2New goldens). */
    @Override
    protected String dateLit(String iso) {
        return "DATE'" + iso + "'";
    }

    @Override
    protected String timestampLit(String iso) {
        // the engine spells the datetime separator as a SPACE
        // (TIMESTAMP'9999-12-31 00:00:00.0000'), never ISO 'T'
        if (iso.length() > 10 && iso.charAt(10) == 'T') {
            iso = iso.substring(0, 10) + ' ' + iso.substring(11);
        }
        // a NON-default connection timeZone CONVERTS datetime constants
        // (engine adjustDate: pure datetimes are GMT; the connection's
        // zone respells them — EST'18:00' prints 13:00)
        if (timeZone != null && !"GMT".equals(timeZone)
                && iso.length() >= 19) {
            try {
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(
                        iso.substring(0, 19).replace(' ', 'T'));
                java.time.ZoneId zone = java.time.ZoneId.of(timeZone,
                        java.time.ZoneId.SHORT_IDS);
                java.time.LocalDateTime shifted = ldt
                        .atZone(java.time.ZoneOffset.UTC)
                        .withZoneSameInstant(zone).toLocalDateTime();
                iso = shifted.toString().replace('T', ' ')
                        + iso.substring(19);
                if (iso.length() == 16) {
                    iso = iso + ":00";
                }
            } catch (java.time.DateTimeException ignored) {
                // unknown zone: spell unshifted (loudness lives in the
                // engine parity diff, not a crash)
            }
        }
        return "TIMESTAMP'" + iso + "'";
    }

    /** The engine-style spelling of an alias AFTER a render pass — the
     * plan printer's resultColumns spell the renamed alias ("root", not
     * t0); call only on the instance that rendered the SQL. */
    public String renderedAlias(String alias) {
        return rename(alias);
    }

    // == single-line lowercase clause assembly ==========================

    /** No MIR passes: engine-text goldens have NO QUALIFY spelling — the
     * select wall below stays LOUD rather than inventing one. */
    @Override
    protected java.util.List<com.legend.sql.SqlRewriter> passes() {
        return java.util.List.of();
    }

    @Override
    protected void query(StringBuilder sb, com.legend.sql.SqlQuery q,
            int depth) {
        // engine union text: one line, lowercase, branches joined inline
        if (q instanceof com.legend.sql.SqlUnion u) {
            String op = u.all() ? " union all " : " union ";
            for (int i = 0; i < u.branches().size(); i++) {
                if (i > 0) {
                    sb.append(op);
                }
                query(sb, u.branches().get(i), depth);
            }
            return;
        }
        super.query(sb, q, depth);
    }

    @Override
    protected void select(StringBuilder sb, SqlSelect s, int depth) {
        if (s.qualify() != null) {
            throw new IllegalStateException(
                    "QUALIFY has no engine-H2 golden spelling");
        }
        sb.append("select ");
        // H2: a bare row cap is TOP; a slice is OFFSET .. FETCH NEXT
        if (s.limit() != null && s.offset() == null) {
            sb.append("top ").append(s.limit()).append(' ');
        }
        if (s.distinct()) {
            sb.append("distinct ");
        }
        sb.append(s.projections().isEmpty() ? "*"
                : s.projections().stream().map(this::projection)
                        .collect(Collectors.joining(", ")));
        if (!(s.from() instanceof SqlSource.Dual)) {
            sb.append(" from ");
            source(sb, s.from(), depth);
        }
        if (s.where() != null) {
            sb.append(" where ").append(whereSql(s.where()));
        }
        if (!s.groupBy().isEmpty()) {
            sb.append(" group by ").append(s.groupBy().stream()
                    .map(e -> groupKey(s, e))
                    .collect(Collectors.joining(", ")));
        }
        if (s.having() != null) {
            sb.append(" having ").append(expr(s.having(), 0));
        }
        if (!s.orderBy().isEmpty()) {
            sb.append(" order by ").append(s.orderBy().stream()
                    .map(this::sortKey).collect(Collectors.joining(", ")));
        }
        if (s.offset() != null) {
            sb.append(" offset ").append(s.offset()).append(" rows");
            if (s.limit() != null) {
                sb.append(" fetch next ").append(s.limit()).append(" rows only");
            }
        }
    }

    /** The WHERE clause text — DB2-family dialects wrap a top-level
     * conjunction in one extra paren pair. */
    protected String whereSql(SqlExpr w) {
        return expr(w, 0);
    }

    /** A group-by key: a key that is PROJECTED UNDER AN ALIAS spells the
     * quoted OUTPUT alias (the engine's TDS group-by-alias text —
     * {@code group by "prodName"}); a bare same-name key (mapping
     * ~groupBy has no alias) keeps the physical expression. Render-only:
     * the IR keys stay real expressions for the execution dialects. */
    protected String groupKey(SqlSelect s, SqlExpr e) {
        if (e instanceof SqlExpr.Column c && c.table() == null) {
            // a SELF-ALIASED key (ENTITY_ID as ENTITY_ID — the view
            // ~groupBy form) spells the PHYSICAL expression (golden:
            // group by "root".ENTITY_ID); only a RENAMING alias keeps
            // the quoted output name (group by "prodName")
            for (SqlSelect.Projection p : s.projections()) {
                if (c.name().equals(p.outputName())
                        && p.expr() instanceof SqlExpr.Column pc
                        && pc.name().equals(c.name())) {
                    return expr(p.expr(), 0);
                }
            }
            return '"' + c.name() + '"';
        }
        for (SqlSelect.Projection p : s.projections()) {
            if (p.outputName() != null && e.equals(p.expr())) {
                // union-frame reads always spell the quoted OUTPUT name
                // (group by "lastName") — the self-alias physical
                // spelling is a VIEW-frame rule
                boolean unionRead = p.expr() instanceof SqlExpr.Column uc
                        && s.from() instanceof SqlSource.Subselect sub
                        && "unionAlias".equals(sub.frameName())
                        && sub.alias().equals(uc.table());
                if (!unionRead && p.expr() instanceof SqlExpr.Column pc
                        && pc.name().equals(p.outputName())) {
                    return expr(e, 0);
                }
                return '"' + p.outputName().replace("\"", "") + '"';
            }
        }
        return expr(e, 0);
    }

    @Override
    protected void source(StringBuilder sb, SqlSource src, int depth) {
        switch (src) {
            case SqlSource.Table t -> {
                sb.append(quoteIdentifiers
                        ? java.util.Arrays.stream(t.name().split("\\.", -1))
                                .map(x -> '"' + x + '"')
                                .collect(java.util.stream.Collectors
                                        .joining("."))
                        : t.name());
                if (t.alias() != null) {
                    sb.append(" as \"").append(rename(t.alias())).append('"');
                }
            }
            case SqlSource.Subselect sub -> {
                sb.append('(');
                // union frames keep the QUOTED alias interior (tds
                // goldens); only VIEW frames unquote their projections
                boolean viewFrame = sub.frameName() != null
                        && !"unionAlias".equals(sub.frameName());
                if (viewFrame) {
                    frameDepth++;
                }
                try {
                    query(sb, sub.inner(), depth);
                } finally {
                    if (viewFrame) {
                        frameDepth--;
                    }
                }
                sb.append(") as \"").append(rename(sub.alias())).append('"');
            }
            case SqlSource.Join j -> {
                source(sb, j.left(), depth);
                sb.append(' ')
                        .append(j.kind() == SqlSource.Join.Kind.INNER
                                ? "inner join"
                                : j.kind().sql.toLowerCase(Locale.ROOT))
                        .append(' ');
                source(sb, j.right(), depth);
                if (j.on() != null) {
                    sb.append(" on (").append(expr(j.on(), 0)).append(')');
                }
            }
            default -> super.source(sb, src, depth);
        }
    }

    /** Nesting depth of NAMED frames (view-backed subselects) currently
     * rendering — their projections spell the engine view generator's
     * form: every column aliased, alias UNQUOTED. */
    private int frameDepth;

    @Override
    protected String projection(SqlSelect.Projection p) {
        String e = expr(p.expr(), 0);
        if (frameDepth > 0 && p.outputName() != null) {
            // engine view SQL: '"root".ORDER_ID as ORDER_ID' — always
            // aliased, unquoted
            return e + " as " + p.outputName().replace("\"", "");
        }
        if (p.alias() == null) {
            return e;
        }
        // a PRE-QUOTED alias (the corpus's '"firstName"' spellings)
        // must not double-wrap — the engine prints one quote level
        String a = p.alias();
        if (a.length() > 1 && a.startsWith("\"") && a.endsWith("\"")) {
            return e + " as " + a;
        }
        return e + " as \"" + a + '"';
    }

    @Override
    protected String expr(SqlExpr e, int parentPrec) {
        // plan-template parameter (engine freemarker): strings are
        // single-quoted with the engine's escape template
        // engine h2New text spells case/when lowercase and float
        // literals as cast(v as float) — the ANSI DOUBLE-cast is a
        // DuckDB execution idiom, not engine text
        if (e instanceof SqlExpr.FloatLit f) {
            return "cast(" + f.value() + " as float)";
        }
        if (e instanceof SqlExpr.PlanParam p) {
            // an OPTIONAL parameter spells the varPlaceHolderToString
            // template in EVERY position (comparisons, null guards) —
            // the selector arm below owns only the equality form
            if (p.optional()) {
                return holder(p);
            }
            return switch (p.kind()) {
                case STRING -> "'${" + p.name()
                        + "?replace(\"'\", \"''\")}'";
                // h2New spells date-typed placeholders with the type
                // keyword (TIMESTAMP'${reportEndDate.date}'); a non-default
                // connection timeZone wraps DATETIME in GMTtoTZ (the same
                // template the optional holder spells)
                // DATE has TWO engine spellings, split by the param's
                // freemarker PATH SHAPE (both in executionPlanTest
                // goldens): a PROPERTY-ACCESSOR param (dotted path —
                // '${reportEndDate.date}', minted by the class-property
                // open-variable arm) spells the h2New TIMESTAMP keyword;
                // a plain function param ('${bd}' — the milestoning
                // business-date channel) spells BARE-QUOTED.
                case DATE -> p.name().indexOf('.') >= 0
                        ? "TIMESTAMP'${" + p.name() + "}'"
                        : "'${" + p.name() + "}'";
                case DATETIME -> timeZone != null
                        ? "TIMESTAMP'${GMTtoTZ( \"[" + timeZone + "]\" "
                                + p.name() + ")}'"
                        : "TIMESTAMP'${" + p.name() + "}'";
                // enum params spell QUOTED, no escape template
                // ('\${yesOrNo}' = 'NO' — testIfEnumParameterInProject)
                case ENUM -> "'${" + p.name() + "}'";
                case FLOAT, BOOLEAN, OTHER -> "${" + p.name() + "}";
            };
        }
        // ENUM parameter comparison: one selector template covers = and
        // in (equalEnumOperationSelector picks by the mapped value's
        // cardinality); negation spells lowercase not around it
        if (e instanceof SqlExpr.Call nc
                && nc.fn() == com.legend.sql.SqlFn.NOT
                && nc.args().size() == 1) {
            String et = enumSelector(nc.args().get(0));
            if (et != null) {
                return "not " + et;
            }
        }
        String et0 = enumSelector(e);
        if (et0 != null) {
            return et0;
        }
        // an OPTIONAL parameter in a comparison renders the engine's
        // freemarker SELECTOR template: present -> the comparison with a
        // varPlaceHolderToString spelling, absent -> the column null test
        if (e instanceof SqlExpr.Call oc
                && oc.fn() == com.legend.sql.SqlFn.EQUAL
                && oc.args().size() == 2) {
            SqlExpr l = oc.args().get(0);
            SqlExpr r = oc.args().get(1);
            // BOTH operands optional: nested selectors — present/present
            // compares placeholders, one-sided null is FALSE (1 = 0),
            // null == null is TRUE (1 = 1)
            if (l instanceof SqlExpr.PlanParam lp2 && lp2.optional()
                    && r instanceof SqlExpr.PlanParam rp2
                    && rp2.optional()) {
                return "(${optionalVarPlaceHolderOperationSelector("
                        + lp2.name()
                        + "![], optionalVarPlaceHolderOperationSelector("
                        + rp2.name() + "![], '" + holder(lp2) + " = "
                        + holder(rp2)
                        + "', '1 = 0'), optionalVarPlaceHolderOperation"
                        + "Selector(" + rp2.name()
                        + "![], '1 = 0', '1 = 1'))})";
            }
            SqlExpr.PlanParam opt = l instanceof SqlExpr.PlanParam lp
                    && lp.optional() ? lp
                    : r instanceof SqlExpr.PlanParam rp && rp.optional()
                            ? rp : null;
            if (opt != null) {
                SqlExpr other = opt == l ? r : l;
                // raw SQL embeds in the single-quoted freemarker clause —
                // its own quotes ESCAPE (the engine's template spelling);
                // holder() text stays as-is (pre-escaped placeholder args)
                String otherTx = expr(other, 4).replace("'", "\\'");
                String present = opt == l
                        ? holder(opt) + " = " + otherTx
                        : otherTx + " = " + holder(opt);
                return "(${optionalVarPlaceHolderOperationSelector("
                        + opt.name() + "![], '" + present
                        + "', '" + otherTx + " is null')})";
            }
        }
        // a property read THROUGH a plan parameter spells the engine's
        // dotted placeholder ('${reportEndDate.date}' — Allocation-bound
        // instance fields in the terminal's SQL)
        if (e instanceof SqlExpr.StructGet sg) {
            if (sg.source() instanceof SqlExpr.PlanParam pp) {
                return "'${" + pp.name() + "." + sg.field() + "}'";
            }
            // engine-H2 text has no struct vocabulary — a named wall
            // (SHAPE in the plan branch), not a dialect bug
            throw new UnsupportedOperationException(
                    "plan: struct extraction has no engine-H2 spelling");
        }
        // alias part quoted, physical column bare — "root".FIRSTNAME;
        // reads of a frame's PROJECTED TDS aliases quote the column too
        // ("persontable_0"."firstName" — rename/union frame goldens)
        if (e instanceof SqlExpr.Column c) {
            if (c.table() != null && quotedFrameRead(c)) {
                return '"' + rename(c.table()) + "\".\"" + c.name() + '"';
            }
            return c.table() == null ? phys(c.name())
                    : '"' + rename(c.table()) + "\"." + phys(c.name());
        }
        String dd = engineDateDiff(e);
        if (dd != null) {
            return dd;
        }
        // engine boolean text: lowercase keywords, AND groups
        // parenthesized once around the flattened chain —
        // '(x is not null and x > y)' (relative-date goldens)
        if (e instanceof SqlExpr.Call bc) {
            switch (bc.fn()) {
                case AND -> {
                    // engine 'and' renders FLAT with no parens at any
                    // arity (extensionDefaults.pure:189) — parens come
                    // only from explicit Group nodes and opposite-operator
                    // nesting (the OR arm below)
                    java.util.List<String> terms = new java.util.ArrayList<>();
                    flattenAnd(bc, terms);
                    return String.join(" and ", terms);
                }
                case OR -> {
                    // and-under-or parenthesizes (the engine's
                    // newAndOrDynaFunctionRelaxedBrackets opposite-operator
                    // group, pureToSQLQuery.pure:5376)
                    java.util.List<String> ops = new java.util.ArrayList<>();
                    for (SqlExpr o : bc.args()) {
                        boolean andLike = o instanceof SqlExpr.Call oc
                                && oc.fn() == com.legend.sql.SqlFn.AND;
                        ops.add(andLike ? "(" + expr(o, 0) + ")"
                                : expr(o, 0));
                    }
                    return String.join(" or ", ops);
                }
                case COALESCE -> {
                    // the null-guarded in() (pure in never returns null;
                    // COALESCE(x in (...), false) is our EXECUTION idiom)
                    // spells the BARE in in engine text — plan-param
                    // templates and literal lists alike; guards may STACK
                    // (in-rule + filter-site), unwrap them all
                    if (bc.args().size() == 2
                            && bc.args().get(1) instanceof SqlExpr.BoolLit bl0
                            && !bl0.value()) {
                        SqlExpr in0 = bc.args().get(0);
                        while (in0 instanceof SqlExpr.Call cc
                                && cc.fn() == com.legend.sql.SqlFn.COALESCE
                                && cc.args().size() == 2
                                && cc.args().get(1)
                                        instanceof SqlExpr.BoolLit bl1
                                && !bl1.value()) {
                            in0 = cc.args().get(0);
                        }
                        if (in0 instanceof SqlExpr.Call ic0
                                && ic0.fn() == com.legend.sql.SqlFn.IN) {
                            return expr(ic0, parentPrec);
                        }
                    }
                }
                case IN -> {
                    // a COLLECTION-typed plan parameter spells the
                    // engine's renderCollection template — separator ","
                    // plus the SAME per-kind prefix/suffix/escape args as
                    // varPlaceHolderToString (in-collection plan goldens)
                    if (bc.args().size() == 2
                            && bc.args().get(1) instanceof SqlExpr.PlanParam cp) {
                        // a non-default connection timeZone spells the
                        // TZ-SHIFTING template (renderCollectionWithTz —
                        // tz second, no escape map) over datetime params
                        if (cp.kind() == SqlExpr.PlanParam.Kind.DATETIME
                                && timeZone != null) {
                            // top-of-sql template — quotes UNESCAPED
                            // (only freemarker-nested args escape)
                            return expr(bc.args().get(0), 4)
                                    + " in (${renderCollectionWithTz("
                                    + cp.name() + "![] \"[" + timeZone
                                    + "]\" \",\" \"TIMESTAMP'\" \"'\""
                                    + " \"null\")})";
                        }
                        return expr(bc.args().get(0), 4)
                                + " in (${renderCollection(" + cp.name()
                                + "![] \",\" " + holderArgs(cp.kind())
                                + " \"null\")})";
                    }
                    // the engine collapses a SINGLETON literal in-list
                    // to equality ('x in ([v])' text = 'x = v')
                    if (bc.args().size() == 2) {
                        return expr(bc.args().get(0), 4) + " = "
                                + expr(bc.args().get(1), 4);
                    }
                    // engine keyword text is lowercase
                    StringBuilder items = new StringBuilder();
                    for (int i = 1; i < bc.args().size(); i++) {
                        if (items.length() > 0) {
                            items.append(", ");
                        }
                        items.append(expr(bc.args().get(i), 0));
                    }
                    return expr(bc.args().get(0), 4) + " in (" + items + ")";
                }
                case IS_NULL -> {
                    return expr(bc.args().get(0), 4) + " is null";
                }
                case IS_NOT_NULL -> {
                    return expr(bc.args().get(0), 4) + " is not null";
                }
                default -> { }
            }
        }
        return super.expr(e, parentPrec);
    }

    private void flattenAnd(SqlExpr e, java.util.List<String> out) {
        if (e instanceof SqlExpr.Call c
                && c.fn() == com.legend.sql.SqlFn.AND) {
            for (SqlExpr a : c.args()) {
                flattenAnd(a, out);
            }
            return;
        }
        // or-under-and parenthesizes (the opposite-operator group,
        // symmetric with the OR arm; engine golden: 'a and (x or y) and
        // b' — the flat join silently REBOUND the chain: '... and x or
        // y and ...' parsed as (… and x) or (y and …)). An explicit
        // Group child wraps ITSELF — no double parens.
        boolean orLike = e instanceof SqlExpr.Call oc
                && oc.fn() == com.legend.sql.SqlFn.OR;
        out.add(orLike ? "(" + expr(e, 0) + ")" : expr(e, 3));
    }

    /** Engine aggregate names are lowercase ({@code sum(}, {@code count(}
     * — every aggregation golden's spelling). */
    @Override
    protected String reducer(com.legend.sql.SqlAgg.Reducer r) {
        // the H2-LENIENT per-group witness spells the BARE expression
        // (view ~groupBy per-row columns — H2 1.x goldens never wrap;
        // our DB-side form is ANY_VALUE, an engine-text-only unwrap)
        if ("ANY_VALUE".equals(r.fn()) && r.args().size() == 1) {
            return expr(r.args().get(0), 0);
        }
        String s = super.reducer(r);
        int p = s.indexOf('(');
        return s.substring(0, p).toLowerCase(Locale.ROOT) + s.substring(p);
    }

    /** Engine sort keys spell the direction EXPLICITLY and lowercase
     * ({@code asc}/{@code desc} — every ordered golden's spelling). */
    @Override
    protected String sortKey(com.legend.sql.SqlSelect.SortKey k) {
        // a COLUMN-NAME-keyed sort (or a table-less column key) is an
        // OUTPUT-column reference (TDS ->sort): the engine spells it
        // quoted — `order by "name" asc`
        String e = k.outputName() != null
                ? '"' + k.outputName().replace("\"", "") + '"'
                : k.expr() instanceof SqlExpr.Column c && c.table() == null
                        ? '"' + c.name() + '"'
                        : expr(k.expr(), 0);
        String s = e + (k.ascending() ? " asc" : " desc");
        // H2 sorts null SMALLEST by default (ASC first / DESC last) and
        // the engine never spells a NULLS clause — suppress a placement
        // that just restates that default (C1.2 puts it in the IR so
        // DuckDB, whose default differs, can render it explicitly)
        var h2Default = k.ascending()
                ? com.legend.sql.SqlSelect.SortKey.NullOrder.NULLS_FIRST
                : com.legend.sql.SqlSelect.SortKey.NullOrder.NULLS_LAST;
        if (k.nullOrder() != null && k.nullOrder() != h2Default) {
            s += k.nullOrder() == com.legend.sql.SqlSelect.SortKey
                    .NullOrder.NULLS_FIRST ? " nulls first" : " nulls last";
        }
        return s;
    }

    /** Engine window text is lowercase: {@code sum(...) over (partition
     * by ... order by ...)} (the window-col goldens' spelling). */
    @Override
    protected String windowCall(SqlExpr.WindowCall w) {
        String s = super.windowCall(w);
        return s.replace(" OVER (", " over (")
                .replace("PARTITION BY ", "partition by ")
                .replace("ORDER BY ", "order by ");
    }

    /**
     * dateDiff's composite IR shapes fold BACK to the engine's plain
     * {@code datediff(unit, a, b)} emission. The IR encodes REAL pure's
     * per-unit semantics (truncated elapsed time; Sunday-boundary weeks —
     * PCT-pinned, Scalars.dateDiffExpr); the engine's H2 SQL emits plain
     * DATEDIFF regardless, and the toSQLString goldens pin that TEXT.
     * The shapes (epoch_ms pairs under integer division; the week CASE)
     * are only produced by the dateDiff lowering.
     */
    private @com.legend.Nullable String engineDateDiff(SqlExpr e) {
        // truncated elapsed: (epoch_ms(end) - epoch_ms(start)) // unitMs
        if (e instanceof SqlExpr.Call div
                && div.fn() == com.legend.sql.SqlFn.INT_DIVIDE
                && div.args().size() == 2
                && div.args().get(1) instanceof SqlExpr.IntLit u
                && div.args().get(0) instanceof SqlExpr.Call minus
                && minus.fn() == com.legend.sql.SqlFn.MINUS
                && minus.args().size() == 2
                && minus.args().get(0) instanceof SqlExpr.Call end
                && end.fn() == com.legend.sql.SqlFn.EPOCH_MS
                && minus.args().get(1) instanceof SqlExpr.Call start
                && start.fn() == com.legend.sql.SqlFn.EPOCH_MS) {
            String unit = switch ((int) u.value()) {
                case 3_600_000 -> "hour";
                case 60_000 -> "minute";
                case 1_000 -> "second";
                default -> null;
            };
            if (unit != null) {
                return "datediff(" + unit + ", " + expr(start.args().get(0), 0)
                        + ", " + expr(end.args().get(0), 0) + ")";
            }
        }
        // Sunday-boundary weeks: CASE WHEN date_diff('day', end, start) <= 0
        // THEN forward ELSE backward. The BRANCHES must also match the
        // sundayIndex-difference shape (audit 19 F1): a user-written
        // if(dateDiff(a,b,DAYS) <= 0, |x, |y) lowers to the SAME guard,
        // and folding it would silently delete both branches.
        if (e instanceof SqlExpr.Case cs && cs.whens().size() == 1
                && cs.otherwise() != null
                && cs.whens().get(0).condition() instanceof SqlExpr.Call le
                && le.fn() == com.legend.sql.SqlFn.LESS_EQUAL
                && le.args().size() == 2
                && le.args().get(0) instanceof SqlExpr.Call dayDiff
                && dayDiff.fn() == com.legend.sql.SqlFn.DATE_DIFF
                && dayDiff.args().get(0) instanceof SqlExpr.StringLit day
                && day.value().equals("day")
                && le.args().get(1) instanceof SqlExpr.IntLit zero
                && zero.value() == 0
                && isSundayIndexDifference(cs.whens().get(0).then())
                && isSundayIndexDifference(cs.otherwise())) {
            return "datediff(week, " + expr(dayDiff.args().get(2), 0)
                    + ", " + expr(dayDiff.args().get(1), 0) + ")";
        }
        // GENERIC case spelling (engine text): lowercase keywords —
        // placed BELOW the specialized recognizers (week-diff) that
        // fold whole CASE shapes into engine idioms
        if (e instanceof SqlExpr.Case c) {
            StringBuilder sb = new StringBuilder("case");
            for (SqlExpr.Case.When w : c.whens()) {
                sb.append(" when ").append(expr(w.condition(), 0))
                        .append(" then ").append(expr(w.then(), 0));
            }
            if (c.otherwise() != null) {
                sb.append(" else ").append(expr(c.otherwise(), 0));
            }
            return sb.append(" end").toString();
        }
        return null;
    }

    /** {@code sundayIndex(d2) - sundayIndex(d1)} where sundayIndex =
     * {@code date_diff('day', DATE '0001-01-07', d) // 7} (Scalars'
     * dateDiff week emission — the only producer of this shape). */
    private static boolean isSundayIndexDifference(SqlExpr e) {
        return e instanceof SqlExpr.Call m
                && m.fn() == com.legend.sql.SqlFn.MINUS
                && m.args().size() == 2
                && isSundayIndex(m.args().get(0))
                && isSundayIndex(m.args().get(1));
    }

    private static boolean isSundayIndex(SqlExpr e) {
        return e instanceof SqlExpr.Call div
                && div.fn() == com.legend.sql.SqlFn.INT_DIVIDE
                && div.args().size() == 2
                && div.args().get(1) instanceof SqlExpr.IntLit seven
                && seven.value() == 7
                && div.args().get(0) instanceof SqlExpr.Call dd
                && dd.fn() == com.legend.sql.SqlFn.DATE_DIFF
                && dd.args().get(1) instanceof SqlExpr.DateLit epoch
                && epoch.iso().equals("0001-01-07");
    }

    // == engine-H2 spellings (each MATCHED against a corpus golden) =====

    /** DurationUnit interval-fn -> H2 dateadd unit keyword
     * (extensionDefaults.pure mapToDBUnitType). */
    private static String dbUnitOf(String unitFn) {
        return switch (unitFn) {
            case "to_years" -> "YEAR";
            case "to_months" -> "MONTH";
            case "to_weeks" -> "WEEK";
            case "to_days" -> "DAY";
            case "to_hours" -> "HOUR";
            case "to_minutes" -> "MINUTE";
            case "to_seconds" -> "SECOND";
            case "to_milliseconds" -> "MILLISECOND";
            case "to_microseconds" -> "MICROSECOND";
            default -> throw new IllegalStateException(
                    "no H2 dateadd unit for interval fn '" + unitFn + "'");
        };
    }

    @Override
    protected String call(SqlExpr.Call c, int parentPrec) {
        java.util.List<SqlExpr> a = c.args();
        return switch (c.fn()) {
            // n-ary concat: nested CONCAT calls SPLICE (the engine emits
            // one flat concat(a, '_', b), never concat(concat(a,'_'),b))
            case CONCAT -> "concat(" + flattenConcat(a).stream()
                    .map(x -> expr(x, 0))
                    .collect(Collectors.joining(", ")) + ")";
            // datediff(<bare unit>, a, b); composite elapsed-time forms
            // built at lowering (weeks/hours/...) have no re-spelling here
            case DATE_DIFF -> a.get(0) instanceof SqlExpr.StringLit u
                    ? "datediff(" + u.value() + ", " + expr(a.get(1), 0)
                            + ", " + expr(a.get(2), 0) + ")"
                    : super.call(c, parentPrec);
            // engine H2 adjust: dateadd(UNIT, n, x) (h2Extension dynaFn
            // 'adjust' + extensionDefaults mapToDBUnitType) — the ANSI
            // base's d + to_days(n) is the DuckDB-executable spelling
            case ADD_INTERVAL -> "dateadd("
                    + dbUnitOf(((SqlExpr.StringLit) a.get(0)).value()) + ", "
                    + expr(a.get(1), 0) + ", " + expr(a.get(2), 0) + ")";
            case CHR -> "char(" + expr(a.get(0), 0) + ")";
            case LENGTH -> "char_length(" + expr(a.get(0), 0) + ")";
            case REVERSE_STRING -> "legend_h2_extension_reverse_string("
                    + expr(a.get(0), 0) + ")";
            case SPLIT_PART -> "legend_h2_extension_split_part("
                    + a.stream().map(x -> expr(x, 0))
                            .collect(Collectors.joining(", ")) + ")";
            case TODAY -> "cast(now() as date)";
            // enum-by-name temporals: the engine's H2 formatdatetime forms.
            // UNMATCHED formats THROW — falling back to strftime() would
            // leak a DuckDB spelling into engine-H2 golden text (audit 19)
            case STRFTIME -> {
                if (a.size() == 2 && a.get(1) instanceof SqlExpr.FormatLit fl
                        && fl.parts().size() == 1
                        && fl.parts().get(0) instanceof com.legend.sql.DateFmt.Part p) {
                    String java = switch (p) {
                        case MONTH_NAME -> "MMMM";
                        case WEEKDAY_NAME -> "EEEE";
                        default -> null;
                    };
                    if (java != null) {
                        yield "formatdatetime(" + expr(a.get(0), 0) + ", '"
                                + java + "')";
                    }
                }
                throw new IllegalStateException("strftime format has no"
                        + " engine-H2 formatdatetime spelling yet: " + a);
            }
            case TRIM -> a.size() == 1
                    ? "trim(both from " + expr(a.get(0), 0) + ")"
                    : super.call(c, parentPrec);
            case DATE_TRUNC_DAY -> "cast(truncate(" + expr(a.get(0), 0)
                    + ") as date)";
            // contains(x, 'lit') lowers strpos(x, lit) > 0; the engine's
            // H2 spelling is the LIKE form (extensionDefaults 'contains'
            // — m2m2rShowcase golden: description like '%RECEIVE CASH%')
            case GREATER -> a.size() == 2
                    && a.get(0) instanceof SqlExpr.Call sp
                    && sp.fn() == com.legend.sql.SqlFn.STRPOS
                    && sp.args().size() == 2
                    && sp.args().get(1) instanceof SqlExpr.StringLit lit
                    && a.get(1) instanceof SqlExpr.IntLit z
                    && z.value() == 0
                    ? expr(sp.args().get(0), 0) + " like '%"
                            + lit.value().replace("'", "''") + "%'"
                    : super.call(c, parentPrec);
            // extract-part goldens spell the SQL-standard extract form
            // (testToSQLString.pure:368 'extract(doy from ...)'; the
            // engine's spelling of that form: oracleExtension.pure:204)
            case EXTRACT -> a.size() == 2
                    && a.get(0) instanceof SqlExpr.StringLit part
                    && "doy".equals(part.value())
                    ? "extract(doy from " + expr(a.get(1), 0) + ")"
                    : super.call(c, parentPrec);
            // firstDayOf* family: every H2 golden spells the uniform
            // double cast; a TODAY anchor renders bare now() inside
            case DATE_TRUNC -> {
                if (a.size() == 2 && a.get(0) instanceof SqlExpr.StringLit u
                        && Set.of("week", "month", "quarter", "year")
                                .contains(u.value())) {
                    String anchor = a.get(1) instanceof SqlExpr.Call tc
                            && tc.fn() == com.legend.sql.SqlFn.TODAY
                            ? "now()" : expr(a.get(1), 0);
                    yield "cast(cast(date_trunc('" + u.value() + "', "
                            + anchor + ") as timestamp) as date)";
                }
                yield super.call(c, parentPrec);
            }
            // parse-date family: the engine's rule (convertToDateH2) is
            // substring(x, 1, 10) + the Java pattern for ALL date-only
            // formats; datetime formats parse the whole string. UNMATCHED
            // formats THROW rather than leak DuckDB strptime() text.
            case STRPTIME -> {
                if (a.size() == 2 && a.get(1) instanceof SqlExpr.FormatLit fl) {
                    String java = h2Pattern(fl);
                    if (java != null) {
                        boolean dateOnly = !fl.parts()
                                .contains(com.legend.sql.DateFmt.Part.HOUR2);
                        yield dateOnly
                                ? "parsedatetime(substring(" + expr(a.get(0), 0)
                                        + ", 1, 10), '" + java + "')"
                                : "parsedatetime(" + expr(a.get(0), 0)
                                        + ", '" + java + "')";
                    }
                }
                throw new IllegalStateException("strptime format has no"
                        + " engine-H2 parsedatetime spelling yet: " + a);
            }
            default -> super.call(c, parentPrec);
        };
    }

    /** TYPED format parts → the Java pattern the engine's parsedatetime
     * takes; null when a part has no mapping (the caller throws — never a
     * silent DuckDB fallback). No format string is ever re-parsed here. */
    private static @com.legend.Nullable String h2Pattern(SqlExpr.FormatLit fl) {
        StringBuilder out = new StringBuilder();
        for (com.legend.sql.DateFmt d : fl.parts()) {
            switch (d) {
                case com.legend.sql.DateFmt.Text t -> out.append(t.s());
                case com.legend.sql.DateFmt.Part p -> {
                    String java = switch (p) {
                        case YEAR4 -> "yyyy";
                        case MONTH2 -> "MM";
                        case DAY2 -> "dd";
                        case HOUR2 -> "HH";
                        case MIN2 -> "mm";
                        case SEC2 -> "ss";
                        default -> null;
                    };
                    if (java == null) {
                        return null;
                    }
                    out.append(java);
                }
            }
        }
        return out.toString();
    }


    @Override
    protected String variantAwareCast(SqlExpr.Cast c) {
        String t = castTypeName(c.target()).toLowerCase(Locale.ROOT);
        // The engine spells casts PER DYNAFUNCTION (audit 19 F3), so only
        // respells that cannot collide survive here:
        // - toDecimal is the ONLY producer of DECIMAL(38, 18) and spells
        //   bare 'decimal'; parseDecimal keeps its declared (p, s).
        // - parseInteger carries SqlType.INTEGER from lowering (no respell;
        //   round/ceiling/floor keep 'bigint', matching their goldens).
        // - 'float': parseFloat's golden spelling. KNOWN LATENT COLLISION:
        //   toFloat shares the DOUBLE IR and the engine spells it 'double
        //   precision' — no corpus H2 golden pins toFloat text today; a
        //   per-dynafunction origin tag is the clean fix when one appears.
        if (t.equals("decimal(38, 18)")) {
            t = "decimal";
        } else if (t.equals("double precision") || t.equals("double")) {
            t = "float";
        }
        return "cast(" + expr(c.value(), 0) + " as " + t + ")";
    }

}
