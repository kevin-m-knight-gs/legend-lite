// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlToPure;
import com.legend.protocol.sql.SqlToPure.Kind;
import com.legend.protocol.sql.SqlToPure.Tx;
import com.legend.protocol.sql.SqlTranslationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The names an {@code #IQ{...}#} island binds outside its SQL (docs/IQ_SQL_STUDY_2026_10_13.md §4 O2 and O3, §9.7; IQ
 * island, 2026-10-14, M1e): a table named without {@code tb()} to one of the model's tables, and a host variable to a
 * variable of the enclosing Pure query. Measured on SQL Anywhere 17 (IQ's front end) over the parity tables.
 *
 * <p><b>Tables (O2).</b> An IQ owner is a schema of the model's Databases, matched as IQ matches names (ASCII
 * case-insensitively); a table declared outside any schema is the island's user's, {@code DBA} (the user IQ's and SQL
 * Anywhere's databases are created with, the oracle's): {@code DBA.t} (and an unqualified {@code t}, and T-SQL's
 * {@code db..t}) reads {@code tb('pkg::Db.t')}, {@code sales.orders} reads {@code tb('pkg::Db.sales.orders')}. A
 * database's and a server's qualifiers are read and ignored, as IQ ignores them ({@code srv.db.owner.t}). The model is
 * the island's catalog:
 * <ul>
 *   <li>an owner that is none of its schemas nor {@code DBA} does not exist, IQ's error -140;</li>
 *   <li>an owner's table that is not there, IQ's -141;</li>
 *   <li>a name more than one Database (or schema of one name but for case) holds is refused by name: write
 *       {@code tb('...')};</li>
 *   <li>IQ's own users ({@code SYS}, {@code dbo}, {@code PUBLIC}, ...) own its catalog, which the island does not
 *       read: refused by name, but {@code SYS.DUMMY} and {@code dbo.sa_rowgenerator(...)}; IQ island, 2026-10-14 (the
 *       third landing): a name of such a user's that is no catalog name's reads the model's table of a schema of
 *       that user's name, when the model has one ({@code dbo.orders} in a T-SQL database).</li>
 * </ul>
 *
 * <p><b>Host variables (O3).</b> {@code @name} and {@code :name} read the enclosing Pure query's variable in scope of
 * that name as IQ matches names, without ASCII case (IQ island, 2026-10-14, review 5's m1, measured with a real
 * variable: {@code @LO} reads {@code @lo}), of its type: an Integer as IQ's BIGINT, a Float as a DOUBLE, a Decimal of a
 * known precision and scale as that NUMERIC, a String as a VARCHAR. A column of {@code @name}'s name in scope is read
 * instead (the research's GRAMMAR L27: a column before a variable); without one, and no variable, it is IQ's -143. An
 * unbound {@code :name} is IQ's -188. A positional {@code ?} names nothing to read: refused by the reader. Refused by
 * name: a variable of any other type, or of many values (and, by the compiler's seam, one named like a lambda parameter
 * of the island's translation, which would hide it); and (review 5) two variables of one name but for case, a variable
 * that may hold no value (M1: lite's empty value read as one raises on Postgres when it plans a comparison with it), a
 * StrictDate or DateTime variable (B3: its value reaches the SQL as a literal lite's Postgres rendering writes with the
 * year's own digits, misread below the year 100). An Integer variable's value is IQ's BIGINT or the read raises -158,
 * and a BIGINT result over it is checked by the island (B1: {@link IqType#loose}); a select-list alias spelled like a
 * variable, which IQ reads first, is refused by the dialect (M2).
 */
final class IqBindings {

    private IqBindings() {
    }

    /** The island's user: the owner of the tables declared outside any schema, and of an unqualified name's. */
    static final String USER = "DBA";

    /** The schema a table declared outside any schema is in, as the model names it. */
    private static final String DEFAULT_SCHEMA = "default";

    /** IQ's and SQL Anywhere's own users (ASCII lower case), whose tables are their catalogs (measured on SQL Anywhere
     *  17: SYS, PUBLIC, dbo, diagnostics, SA_DEBUG, rs_systabgroup, and the SYS_..._ROLE roles). */
    private static final Set<String> SYSTEM_OWNERS = Set.of("sys", "dbo", "public", "diagnostics", "sa_debug",
            "rs_systabgroup");

    // ------------------------------------------------------------------ tables

    /** The owner {@code name} is qualified by, as written (a database's and a server's qualifiers ignored); null for an
     *  unqualified name or T-SQL's {@code db..t}, its owner omitted. */
    static SqlAst.@com.legend.base.Nullable Ident owner(List<SqlAst.Ident> name) {
        if (name.size() < 2) {
            return null;
        }
        SqlAst.Ident owner = name.get(name.size() - 2);
        return owner.text().isEmpty() ? null : owner;
    }

    /** Whether {@code owner} (as written) is one of IQ's own users. */
    static boolean systemOwner(SqlAst.Ident owner) {
        String o = IqLexer.lower(owner.text());
        return SYSTEM_OWNERS.contains(o) || o.startsWith("sys_");
    }

    /** The beginnings (ASCII lower case) of the names of IQ's and SQL Anywhere's catalog tables and views (SQL
     *  Anywhere 17's, listed from its own catalog; IQ's sp_iq and SYSIQ objects), and its other names of one. */
    private static final List<String> CATALOG_PREFIXES = List.of("sys", "isys", "gtsys", "igtsys", "iq", "sp_", "sa_",
            "satmp", "spt_", "st_", "jdbc_", "ix_consultant", "migrate_", "omni_", "synchronize_", "gtt_", "rs_");
    private static final Set<String> CATALOG_NAMES = Set.of("dummy", "rowgenerator", "excludeobject");

    /** Whether {@code name} (as written) is, or may be, the name of one of IQ's catalog tables or views. */
    static boolean catalogName(String name) {
        String n = IqLexer.lower(name);
        if (CATALOG_NAMES.contains(n)) {
            return true;
        }
        for (String prefix : CATALOG_PREFIXES) {
            if (n.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Refuses by name, before translation, a table of IQ's catalog: one an owner of IQ's own qualifies (but
     *  {@code SYS.DUMMY}, which the dialect reads). IQ island, 2026-10-14 (the third landing): one whose name is no
     *  catalog name's is bound when translated, to a schema of the model of that owner's name ({@link #table}). */
    static void catalogTable(SqlAst.TableName t) {
        SqlAst.Ident owner = owner(t.name());
        if (owner != null && systemOwner(owner) && catalogName(t.name().get(t.name().size() - 1).text())) {
            throw catalogRefusal(t, owner);
        }
    }

    private static SqlTranslationException catalogRefusal(SqlAst.TableName t, SqlAst.Ident owner) {
        return SqlTranslationException.unsupported("the table " + written(t.name()) + " is not supported: '"
                + owner.text() + "' is one of IQ's own users, whose tables are its catalog, which an #IQ{...}#"
                + " island does not read", t.pos());
    }

    /**
     * {@code t}'s relation: the one table of {@code catalog} its owner and its name name (see the class doc), as
     * {@code #>{db.schema.table}#}; IQ's error -140 or -141, or a refusal by name, when there is not exactly one.
     */
    static ValueSpecification table(SqlAst.TableName t, List<SqlToPure.CatalogTable> catalog) {
        SqlAst.Ident owner = owner(t.name());
        SqlAst.Ident table = t.name().get(t.name().size() - 1);
        String wanted = IqLexer.lower(table.text());
        if (owner != null && systemOwner(owner)) {
            // IQ island, 2026-10-14 (the third landing): one of IQ's own users' names that is no catalog name's reads
            // the model's table of a schema of that user's name (a T-SQL database's dbo.orders); with none, it would
            // be IQ's catalog's, and stays refused by name
            boolean held = false;
            for (SqlToPure.CatalogTable c : catalog) {
                held |= IqLexer.lower(c.schema()).equals(IqLexer.lower(owner.text()))
                        && IqLexer.lower(c.table()).equals(wanted);
            }
            if (!held) {
                throw catalogRefusal(t, owner);
            }
        }
        boolean user = owner == null || IqLexer.lower(owner.text()).equals(IqLexer.lower(USER));
        boolean known = user;
        List<SqlToPure.CatalogTable> found = new ArrayList<>();
        for (SqlToPure.CatalogTable c : catalog) {
            String schema = IqLexer.lower(c.schema());
            boolean owned = owner != null && schema.equals(IqLexer.lower(owner.text()))
                    || user && (c.schema().equals(DEFAULT_SCHEMA) || schema.equals(IqLexer.lower(USER)));
            known |= owned;
            if (owned && IqLexer.lower(c.table()).equals(wanted)) {
                found.add(c);
            }
        }
        if (found.size() > 1) {
            List<String> paths = new ArrayList<>();
            found.forEach(c -> paths.add(path(c)));
            throw SqlTranslationException.unsupported("the table " + written(t.name()) + " names more than one table of"
                    + " the model (" + String.join(", ", paths) + "): write tb('...') for the one meant", t.pos());
        }
        if (found.isEmpty()) {
            if (owner == null) {
                throw SqlTranslationException.unsupported("the table '" + table.text() + "' is no table of the island's"
                        + " user, " + USER + " (a table of the model declared outside any schema, or in a schema " + USER
                        + "): IQ would look among its catalog's too, which an #IQ{...}# island does not read; qualify"
                        + " it by its owner, or write tb('...')", t.pos());
            }
            if (!known) {
                throw SqlTranslationException.invalid("the user '" + owner.text() + "' does not exist: an owner is a"
                        + " schema of the model's Databases, or " + USER + " for a table declared outside any schema"
                        + IqDialect.code(-140, "08004"), owner.pos());
            }
            throw SqlTranslationException.invalid("the table '" + table.text() + "' of '" + owner.text() + "' is not"
                    + " found" + IqDialect.code(-141, "42W33"), table.pos());
        }
        SqlToPure.CatalogTable c = found.get(0);
        return AppliedFunction.tableReference(c.database(), c.schema().equals(DEFAULT_SCHEMA) ? c.table()
                : c.schema() + "." + c.table(), null);
    }

    /** Whether {@code c} is a table of the island's user, {@link #USER}: declared outside any schema, or in a schema
     *  of that name (IQ island, 2026-10-14, review 5's M3). */
    static boolean usersTable(SqlToPure.CatalogTable c) {
        return c.schema().equals(DEFAULT_SCHEMA) || IqLexer.lower(c.schema()).equals(IqLexer.lower(USER));
    }

    private static String path(SqlToPure.CatalogTable c) {
        return c.database() + "." + (c.schema().equals(DEFAULT_SCHEMA) ? "" : c.schema() + ".") + c.table();
    }

    private static String written(List<SqlAst.Ident> name) {
        List<String> parts = new ArrayList<>();
        name.forEach(i -> parts.add(i.text()));
        return String.join(".", parts);
    }

    // ------------------------------------------------------------------ host variables

    /**
     * {@code p}, a named parameter ({@code @name}, {@code :name}), {@code at} its place: a column of {@code @name}'s
     * name in scope, else the enclosing Pure query's variable in scope ({@code sources}), by its type (see the class
     * doc).
     */
    static Tx parameter(SqlAst.Param p, SqlToPure.At at, SqlToPure.Sources sources) {
        String written = p.name();
        if (written == null) {
            throw SqlTranslationException.unsupported("a positional parameter is not supported", p.pos());
        }
        if (readsColumn(p, at)) {
            return at.tx(column(p));
        }
        boolean host = written.startsWith(":");
        String name = scoped(written.substring(1), sources, p);
        SqlToPure.VariableType type = SqlToPure.variable(sources, name);
        if (type == null) {
            throw host ? SqlTranslationException.invalid("no variable '" + name + "' of the enclosing Pure query is in"
                    + " scope to read as the host variable " + written + IqDialect.code(-188, "07001"), p.pos())
                    : SqlTranslationException.invalid("no column '" + written + "' in the query's tables, and no"
                    + " variable '" + name + "' of the enclosing Pure query in scope" + IqDialect.code(-143, "52003"),
                    p.pos());
        }
        if (type.many()) {
            throw SqlTranslationException.unsupported("the variable '" + name + "' may hold more than one value, which"
                    + " " + written + " cannot read", p.pos());
        }
        if (type.nullable()) {
            // IQ island, 2026-10-14 (review 5's M1): an empty value read as one ({@code toOne}) is a raise Postgres
            // folds while planning a comparison with it, whichever branch would run (docs/IQ_SQL_STUDY_2026_10_13.md
            // §9.14)
            throw SqlTranslationException.unsupported("the variable '" + name + "' may hold no value (its"
                    + " multiplicity is [0..1]), which " + written + " does not read yet: lite's reading of an empty"
                    + " variable as IQ's NULL raises on Postgres when it plans a comparison with it; give it one value"
                    + " ([1]), or test it outside the island", p.pos());
        }
        String pure = type.type();
        IqType detail;
        Kind kind;
        ValueSpecification v = new Variable(name);
        if (pure.equals("Integer")) {
            // IQ island, 2026-10-14 (review 5's B1): IQ's BIGINT holds the value or the read raises -158 (a Pure
            // Integer may hold more); the database may compute it wider than 64 bits (its literal widened near 2^63),
            // so a BIGINT result over it is checked here (IqType.loose), not left to the database's overflow
            kind = Kind.INTEGER;
            detail = IqType.integer("BIGINT", IqType.BIGINT_MIN, IqType.BIGINT_MAX, true, true);
            v = bigint(v);
        } else if (pure.equals("Float")) {
            kind = Kind.FLOAT;
            detail = new IqType("DOUBLE");
        } else if (pure.equals("String")) {
            kind = Kind.STRING;
            detail = new IqType("VARCHAR");
        } else if (pure.equals("StrictDate") || pure.equals("DateTime")) {
            // IQ island, 2026-10-14 (review 5's B3): the value reaches the SQL as a literal, which lite's Postgres
            // rendering writes with the year's own digits ('12-12-12'): Postgres reads a year below 100 as this
            // century's, and the value is not known here to refuse it (review 3's B3 refuses such a constant)
            throw SqlTranslationException.unsupported("the variable '" + name + "' is a " + pure + ", which " + written
                    + " does not read yet: its value reaches the SQL as a literal that lite's Postgres rendering"
                    + " writes with its year's own digits, which Postgres misreads below the year 100; compare it"
                    + " outside the island, or write the date in the SQL", p.pos());
        } else if (pure.startsWith("Decimal(") && pure.endsWith(")")) {
            kind = Kind.DECIMAL;
            detail = new IqType("NUMERIC" + pure.substring("Decimal".length()).replace(" ", ""));
        } else {
            throw SqlTranslationException.unsupported("the variable '" + name + "' is of type " + pure + ", which " + written
                    + " does not read: an #IQ{...}# island reads an Integer, a Float, a Decimal of a known precision and"
                    + " scale or a String", p.pos());
        }
        return Tx.value(v, kind, false, detail);
    }

    /** Whether {@code p} reads a column of its name ({@code @name} only, GRAMMAR L27: a column before a variable). */
    static boolean readsColumn(SqlAst.Param p, SqlToPure.At at) {
        String written = p.name();
        return written != null && !written.startsWith(":") && at.hasColumn(column(p));
    }

    private static SqlAst.ColumnRef column(SqlAst.Param p) {
        return new SqlAst.ColumnRef(List.of(new SqlAst.Ident(java.util.Objects.requireNonNull(p.name()), false,
                p.pos())), p.pos());
    }

    /**
     * The enclosing query's variable {@code name} reads, as IQ matches names (IQ island, 2026-10-14, review 5's m1 and
     * M5, measured with a real variable: {@code @LO} reads {@code @lo}): the one variable in scope of that name but for
     * ASCII case; refused by name when there are more (IQ has one variable per name, Pure's names are case-sensitive);
     * {@code name} itself when none matches (or the caller reports no names), for the exact lookup to answer.
     */
    private static String scoped(String name, SqlToPure.Sources sources, SqlAst.Param p) {
        List<String> alike = new ArrayList<>();
        for (String v : SqlToPure.variables(sources)) {
            if (IqLexer.lower(v).equals(IqLexer.lower(name))) {
                alike.add(v);
            }
        }
        if (alike.size() > 1) {
            throw SqlTranslationException.unsupported("IQ matches a variable's name without case, and the enclosing"
                    + " Pure query has more than one variable named " + p.name() + " but for case ("
                    + String.join(", ", alike) + "): rename them apart", p.pos());
        }
        return alike.size() == 1 ? alike.get(0) : name;
    }

    /** {@code v}, an Integer variable's value, as IQ's BIGINT: IQ's -158 when BIGINT cannot hold it. Tested through
     *  differences ({@code v - max > 0}, {@code v + max < -1}), which lite's databases compute wider than 64 bits:
     *  lite's planner folds a comparison of two constants with {@code Long.MAX_VALUE} as if no Integer passed it. */
    private static ValueSpecification bigint(ValueSpecification v) {
        CInteger max = SqlToPure.integer(IqType.BIGINT_MAX);
        ValueSpecification above = SqlToPure.call("greaterThan", AppliedFunction.infixRun("minus", List.of(v, max)),
                new CInteger(0L));
        ValueSpecification below = SqlToPure.call("lessThan", AppliedFunction.infixRun("plus", List.of(v, max)),
                new CInteger(-1L));
        return IqDialect.ifThen(SqlToPure.call("or", above, below), IqDialect.raise(), v);
    }

    /** Whether {@code e} is a host variable (a named parameter). */
    static boolean hostVariable(SqlAst.Expr e) {
        return e instanceof SqlAst.Param p && p.name() != null;
    }
}
