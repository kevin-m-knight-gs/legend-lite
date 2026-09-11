// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.builtin;

/**
 * The legacy TDS vocabulary (upstream {@code tds.pure} spellings — functions
 * with pure bodies the platform desugars at typing instead of carrying as
 * catalog rows) as a CLOSED set: each member is its upstream identity, and
 * {@link #matches} is the one place an applied name is read against it — the
 * exact FQN or the bare name, never a suffix (the exact-FQN identification
 * rule; the 2026-09-11 simple-name census retired {@code Typer.simpleFnName}
 * and {@code tdsVocab} into this enum).
 *
 * <p>These are NOT platform inventions: every member names a function
 * upstream declares — under {@code meta::pure::tds} (restrict, tdsRows,
 * columnValues, olapGroupBy, …), a TDS-era column-spec form (col, func,
 * window, columnByName), or the {@code math::olap} rank functions the legacy
 * olapGroupBy lambdas call. Carrying their BODIES as prelude functions instead
 * of typer desugars is an open finding of the batch-5 audit.
 */
public enum TdsLegacy {
    COL("meta::pure::tds::col"),
    FUNC("meta::pure::tds::func"),
    WINDOW("meta::pure::tds::window"),
    COLUMN_BY_NAME("meta::pure::tds::columnByName"),
    COLUMN_VALUES("meta::pure::tds::columnValues"),
    OLAP_GROUP_BY("meta::pure::tds::olapGroupBy"),
    PROJECT("meta::pure::tds::project"),
    PROJECT_WITH_COLUMN_SUBSET("meta::pure::tds::projectWithColumnSubset"),
    RENAME_COLUMN("meta::pure::tds::renameColumn"),
    RENAME_COLUMNS("meta::pure::tds::renameColumns"),
    RESTRICT("meta::pure::tds::restrict"),
    RESTRICT_DISTINCT("meta::pure::tds::restrictDistinct"),
    TDS_ROWS("meta::pure::tds::tdsRows"),
    /** the legacy olap rank lambdas ({@code y|$y->rank()}) — upstream
     *  mathExtension.pure's {@code math::olap} functions; the olapGroupBy
     *  desugar maps them to the modern window functions */
    OLAP_RANK("meta::pure::functions::math::olap::rank"),
    OLAP_DENSE_RANK("meta::pure::functions::math::olap::denseRank"),
    OLAP_ROW_NUMBER("meta::pure::functions::math::olap::rowNumber"),
    OLAP_AVERAGE_RANK("meta::pure::functions::math::olap::averageRank");

    private final String fqn;

    TdsLegacy(String fqn) {
        this.fqn = fqn;
    }

    public String bare() {
        return fqn.substring(fqn.lastIndexOf("::") + 2);
    }

    public String fqn() {
        return fqn;
    }

    /** Whether an applied (pre-resolution) name spells this form exactly —
     *  its upstream FQN or its bare name. */
    public boolean matches(String appliedName) {
        return appliedName.equals(bare()) || appliedName.equals(fqn);
    }

    /** Whether the applied function spells this form. */
    public boolean matches(com.legend.protocol.spec.AppliedFunction af) {
        return matches(af.function());
    }
}
