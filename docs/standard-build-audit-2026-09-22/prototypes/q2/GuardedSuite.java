package com.legend;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Q2 REMEDY — THE CASE-COUNT FLOOR, asserted INSIDE suite().
 *
 * Every runner tried (Bazel's BazelTestRunner, contrib_rules_jvm's
 * java_junit5_test + vintage, and the JUnit 5 ConsoleLauncher even with
 * --fail-if-no-tests) reports GREEN when the framework generates zero
 * cases. Two of the three also write a test.xml that claims tests="1",
 * so an XML-count guard outside the JVM is not enough either.
 *
 * The only runner-independent detector is a floor the SUITE ITSELF
 * asserts, at build time, before handing the suite over. Bolt it into
 * PctCensusGate.wrap(...) beside the census ceilings; the floor is a
 * ratchet, exactly like MAX_UNTYPED.
 */
public class GuardedSuite {

    /** MEASURED, and it only ever goes UP. */
    private static final int MIN_CASES = 5;

    public static Test suite() {
        int n = Integer.getInteger("q2.count", 7);
        TestSuite s = new TestSuite("GuardedSuite(runtime)");
        for (int i = 0; i < n; i++) {
            final int k = i;
            s.addTest(new TestCase("generated_" + i) {
                @Override protected void runTest() { System.out.println("[q2] ran generated_" + k); }
            });
        }
        return wrap("Guarded", s);
    }

    static Test wrap(String suiteName, TestSuite t) {
        int n = t.countTestCases();
        if (n < MIN_CASES) {
            // thrown from suite(); every runner surfaces this as an ERROR
            throw new AssertionError("[pct-floor] " + suiteName + ": the framework generated "
                    + n + " test cases, floor is " + MIN_CASES
                    + " — the suite did not build (missing PAR/repository/adapter),"
                    + " it did not pass");
        }
        return new TestSetup(t) {
            @Override protected void tearDown() {
                System.out.println("[q2] TEARDOWN: " + n + " cases ran");
            }
        };
    }
}
