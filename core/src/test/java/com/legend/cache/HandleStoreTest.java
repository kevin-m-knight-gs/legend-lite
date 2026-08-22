// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * D5 pins for {@link HandleStore}: the properties whose ABSENCE was the
 * audit finding against the old ConnectionResolver map — check-then-act
 * race (two opens, leaked loser), no dead-handle replacement, and
 * name-keyed identity.
 */
class HandleStoreTest {

    private record Handle(int id, boolean dead) {
    }

    @Test
    void sameKeySameHandle() throws Exception {
        HandleStore<Handle> store = new HandleStore<>();
        Hash k = Hash.ofUtf8("a");
        Handle first = store.getOrOpen(k, Handle::dead, () -> new Handle(1, false));
        Handle again = store.getOrOpen(k, Handle::dead, () -> new Handle(2, false));
        assertSame(first, again, "live handle must be reused, not reopened");
    }

    @Test
    void distinctKeysDistinctHandles() throws Exception {
        HandleStore<Handle> store = new HandleStore<>();
        Handle a = store.getOrOpen(Hash.ofUtf8("a"), Handle::dead,
                () -> new Handle(1, false));
        Handle b = store.getOrOpen(Hash.ofUtf8("b"), Handle::dead,
                () -> new Handle(2, false));
        assertNotSame(a, b, "content keys are identity — no cross-key sharing");
    }

    @Test
    void deadHandleIsReplaced() throws Exception {
        HandleStore<Handle> store = new HandleStore<>();
        Hash k = Hash.ofUtf8("a");
        store.getOrOpen(k, Handle::dead, () -> new Handle(1, true));
        Handle replacement = store.getOrOpen(k, Handle::dead,
                () -> new Handle(2, false));
        assertEquals(2, replacement.id(), "a dead handle must be replaced");
    }

    @Test
    void checkedExceptionPropagatesAndCachesNothing() throws Exception {
        HandleStore<Handle> store = new HandleStore<>();
        Hash k = Hash.ofUtf8("a");
        assertThrows(java.io.IOException.class, () -> store.getOrOpen(k,
                Handle::dead, () -> {
                    throw new java.io.IOException("boom");
                }));
        Handle afterFailure = store.getOrOpen(k, Handle::dead,
                () -> new Handle(7, false));
        assertEquals(7, afterFailure.id(), "a failed open must not poison the key");
    }

    /** THE race pin: N threads demanding one key must produce ONE open —
     * the old check-then-act let several through and leaked the losers. */
    @Test
    void concurrentDemandOpensOnce() throws Exception {
        HandleStore<Handle> store = new HandleStore<>();
        Hash k = Hash.ofUtf8("a");
        AtomicInteger opens = new AtomicInteger();
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] pool = new Thread[threads];
        Handle[] got = new Handle[threads];
        for (int i = 0; i < threads; i++) {
            int slot = i;
            pool[i] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    got[slot] = store.getOrOpen(k, Handle::dead,
                            () -> new Handle(opens.incrementAndGet(), false));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            pool[i].start();
        }
        ready.await();
        go.countDown();
        for (Thread t : pool) {
            t.join();
        }
        assertEquals(1, opens.get(), "atomic compute admits exactly one open");
        for (Handle h : got) {
            assertSame(got[0], h, "every demander sees the single opened handle");
        }
    }
}
