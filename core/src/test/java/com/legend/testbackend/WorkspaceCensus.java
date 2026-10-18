// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import java.util.concurrent.atomic.AtomicLong;

/** The cost of closing the corpus's workspaces (perf homework 2026-09-20), counted by the backend whose workspaces
 *  close by detaching a catalog and printed by the corpus run: how many closed, their total and their longest. */
public final class WorkspaceCensus {

    public static final AtomicLong DETACHES = new AtomicLong();
    public static final AtomicLong DETACH_NANOS = new AtomicLong();
    public static final AtomicLong DETACH_MAX_NANOS = new AtomicLong();

    private WorkspaceCensus() {
    }

    /** One close that took {@code nanos}. */
    static void closed(long nanos) {
        DETACHES.incrementAndGet();
        DETACH_NANOS.addAndGet(nanos);
        DETACH_MAX_NANOS.accumulateAndGet(nanos, Math::max);
    }
}
