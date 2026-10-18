// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * A clashing fixture set aside in a corpus workspace ({@link TestBackend#setFixtureAside}): the fixture's tables live
 * apart from the workspace's own, under the names it writes, and once it has run ({@link #restore}) the workspace reads
 * its own tables first and every aside's after them (the runner's mid-session rule, {@code test.PureTestRunner}).
 */
public interface FixtureAside {

    /** Its name, as the run's log and the referee's mirror call it. */
    String name();

    /** The connection the fixture's setup runs on: the workspace itself, or a connection of its own pointed at the
     *  aside, which {@link #restore} closes. */
    Connection connection();

    /** Every aside the workspace holds, this one last: the order it reads them in after its own tables. */
    List<String> held();

    /** The fixture has run: the workspace reads its own tables first, then every aside it holds ({@link #held}). */
    void restore() throws SQLException;
}
