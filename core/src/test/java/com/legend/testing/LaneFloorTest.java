// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lanes' floors are LIVE: every key of {@code lanes/<lane>.txt} names an integration test that exists (its class,
 * nested with {@code $}, and its method), so a line cannot outlive its test by a rename or a deletion -- a floor
 * only shrinks when the lane says so, never silently (track T, T-d2, 2026-10-29; the T-d audit's finding 11). A line
 * that pins a test as NOT RUN on the lane carries its date and cause ({@link LaneFloor#NOT_RUN}).
 */
class LaneFloorTest {

    private static final List<String> LANES = List.of("sqlite", "h2");

    @Test
    void everyFloorLineNamesATestThatExists() throws Exception {
        List<String> dead = new ArrayList<>();
        int lines = 0;
        for (String lane : LANES) {
            Map<String, String> floor = LaneFloor.floor(lane);
            assertTrue(!floor.isEmpty(), "the " + lane + " lane's floor is empty or missing");
            for (String key : floor.keySet()) {
                lines++;
                String reason = floor.get(key);
                if (reason.startsWith(LaneFloor.NOT_RUN) && !reason.matches("not run \\d{4}-\\d{2}-\\d{2} .*")) {
                    dead.add(lane + ": " + key + " (a not-run pin without its date and cause: '" + reason + "')");
                }
                String cls = key.substring(0, key.indexOf('#'));
                String method = key.substring(key.indexOf('#') + 1);
                // an invocation's ordinal is not the method's name
                int bracket = method.indexOf('[');
                if (bracket >= 0) {
                    method = method.substring(0, bracket);
                }
                try {
                    Class<?> c = Class.forName("com.legend.integration." + cls);
                    boolean found = false;
                    for (Method m : c.getDeclaredMethods()) {
                        found |= m.getName().equals(method);
                    }
                    if (!found) {
                        dead.add(lane + ": " + key + " (no such method)");
                    }
                } catch (ClassNotFoundException e) {
                    dead.add(lane + ": " + key + " (no such class)");
                }
            }
        }
        assertEquals(List.of(), dead, "floor lines naming no test: remove them, with the reason in the commit");
        assertTrue(lines > 0);
    }
}
