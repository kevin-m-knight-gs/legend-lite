// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The query/statement split (One-Platform Plan Phase 1c): a single
 * row-producing READ types as a relation; DDL/DML/multi-statement
 * blobs stay on the opaque effect path. These pins hold the gate's
 * exact boundary.
 */
class RawSqlTest {

    @Test
    void singleSelectIsAQuery() {
        assertTrue(RawSql.isSingleQuery("select 1 as A"));
        assertTrue(RawSql.isSingleQuery("  SELECT * FROM t"));
        assertTrue(RawSql.isSingleQuery("with c as (select 1) select * from c"));
        assertTrue(RawSql.isSingleQuery("VALUES (1), (2)"));
    }

    @Test
    void commentsDoNotHideTheKeyword() {
        assertTrue(RawSql.isSingleQuery("-- header\nselect 1"));
        assertTrue(RawSql.isSingleQuery("/* block */ select 1"));
        assertFalse(RawSql.isSingleQuery("-- only a comment"));
    }

    @Test
    void ddlAndDmlAreEffects() {
        assertFalse(RawSql.isSingleQuery("create table t(x int)"));
        assertFalse(RawSql.isSingleQuery("Drop table if exists t"));
        assertFalse(RawSql.isSingleQuery("insert into t values (1)"));
        assertFalse(RawSql.isSingleQuery("update t set x = 1"));
        assertFalse(RawSql.isSingleQuery("delete from t"));
    }

    @Test
    void multiStatementBlobsAreEffects() {
        assertFalse(RawSql.isSingleQuery("select 1; select 2"));
        assertFalse(RawSql.isSingleQuery(
                "drop table if exists t; create table t(x int); insert into t values (1)"));
        // a ; inside a string literal does NOT split (string-aware)
        assertTrue(RawSql.isSingleQuery("select 'a;b' as A"));
        // trailing ; is still one statement
        assertTrue(RawSql.isSingleQuery("select 1;"));
    }
}
