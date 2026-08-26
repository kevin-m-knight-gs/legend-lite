// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct;

import com.legend.exec.SqlTypeCensus;
import junit.extensions.TestSetup;
import junit.framework.Test;

/**
 * M4 §3.4 — THE PCT CENSUS GATE: the G6/G7 JVMs run the same
 * {@link SqlTypeCensus} instruments as every other lane, but until
 * this hook nothing ASSERTED them there — the suites measured and
 * discarded. Each PCT suite's teardown now pins the lane.
 *
 * <p>Counters are CUMULATIVE PER JVM (the trap roster: measure lanes
 * whole, never per-suite), and one surefire JVM runs every pct test
 * class in file order — so per-suite deltas are meaningless and only
 * ORDER-SAFE facts are asserted at each teardown: never-happens
 * invariants ({@code mismatch == 0} — a label lie escaped
 * reconciliation) and whole-JVM ceilings (a teardown observes a prefix
 * of the JVM's traffic, so a prefix exceeding the JVM ceiling is
 * already a regression). Ceilings are MEASURED per lane (2026-08-25)
 * and ratchet DOWN as families burn; the h2 lane (G7,
 * {@code LEGENDLITE_PCT_BACKEND=h2}) is its own JVM with its own
 * numbers.
 */
public final class PctCensusGate {

    private PctCensusGate() {
    }

    private static final boolean H2 =
            "h2".equals(System.getenv("LEGENDLITE_PCT_BACKEND"));

    // MEASURED 2026-08-25 at the last teardown of each lane's JVM:
    // G6 full pct JVM on DuckDB (after Grammar, incl. ChannelB traffic)
    //   mismatch=0 untyped=813 adopt-pending=101 diverge=78
    // G7 h2 Relation JVM: mismatch=0 untyped=17 adopt-pending=0 diverge=0
    // untyped 813 -> 808 banked at the M4 re-land (typed claim roots);
    // the lane's one NEW class is wire-delivered LITERAL <- VARCHAR —
    // the registered carrier pair, adjudicated by design (F10).
    // 808 -> 728 (2026-08-25 rules burn: label-less ScalarSubquery
    // projection read + probed date_trunc/decimal-arithmetic/window
    // rules + empty-ArrayLit-is-Nil). MEASURED on the gate's OWN
    // composition (unfiltered `mvn clean test`, ONE cumulative JVM,
    // final teardown after Grammar = 728). A first ratchet to 336 was
    // a WRONG-DENOMINATOR measurement — a -Dtest-filtered run has a
    // different JVM composition; counters are cumulative per JVM, so
    // this pin only ever moves on the unfiltered gate command's
    // numbers (the G6 chain trip that caught it: Standard's teardown
    // alone reads 611).
    // 728 -> 273 (pct-tail burn: list_max/min element identity,
    // ADD_INTERVAL -> TIMESTAMP, bit-op widest-int, greatest/least
    // branch promotion) -> 120 (2026-08-25 FULL burn: XOR/REPEAT_STR/
    // TIMEZONE groups, list_sum/avg/median/mode via the reducer
    // promotions, list_product/append/reduce, the map family
    // (concat/from_entries/extract/keys/values), SPLIT/
    // REGEXP_EXTRACT_ALL -> VARCHAR[], FoldCall body-and-init rule,
    // decimal-mix union promotion in branchPromote — every rule
    // probed on the 1.5.0 reference jar). 120 -> 108: HASH -> BIGINT
    // (our OWN renderer reinterprets to signed BIGINT — the CEILING
    // rule-vs-emission mistake repeated; probe the emission, not the
    // bare builtin). 108 -> 20 (§4bZ-U EXECUTION, 2026-08-25 — the
    // five legs, each G4-witnessed): demand-driven pivot stamps (36
    // Column + 2 Reducer; the stamp speaks the Reducer's EMISSION
    // fact, not the pure contract — the first cut re-ran the CEILING
    // mistake and the wire census caught it); the RAISES fact (9
    // error() rows now counted raises=, never type debt); fetchDb
    // DECLARED JDBC-spec schemas; the binding-door sweep (fold
    // element+accumulator, Comparators element, minus-fold
    // LIST_REDUCE params, collection-map element door) + the
    // typedList conform-by-emission door (empty/NULL list positions
    // cast to their pure element's array: zip/joinStrings/fold init)
    // + declared struct-field slots (an absent optional property's
    // NULL contributes its layout type) + the REM decimal rule
    // (probed union shape). THE 20 = the receipted remainder
    // (witnesses per sweep, full composition only — a scoped
    // Essential run shows 3): 14x StructGet + 6x UNNEST across the
    // fold/instance-payload chains — mixed-struct LIST_CONCAT (a
    // heterogeneous class LUB, a TRUE payload shape), the FoldCall
    // collection-accumulator (the accIsList carrier's declared
    // no-rule), chained LIST_REDUCE(LIST_TRANSFORM) reductions, and
    // composition-dependent fold-channel struct chains. Each is a
    // named receipt, not a silent cap; rules for them must type THE
    // EMISSION or stay honest.
    private static final long MAX_UNTYPED = H2 ? 17 : 20;
    // diverge 78 -> 46, adopt-pending 101 -> 64 (measured with the
    // §4bZ-U legs: emission-true pivot stamps + REM labels heal wire
    // agreement; the pct-lane wire ledgers' adjudication itself is
    // still the §4bZ-V C charter item).
    private static final long MAX_WIRE_DIVERGE = H2 ? 0 : 46;
    private static final long MAX_ADOPT_PENDING = H2 ? 0 : 64;

    public static Test wrap(String suite, Test t) {
        return new TestSetup(t) {
            @Override
            protected void tearDown() {
                System.out.println("[pct-census] after " + suite + ": "
                        + SqlTypeCensus.summary());
                // the untyped DECOMPOSITION (the corpus lane's census
                // display, brought to the pct lane 2026-08-25 — no
                // silent caps: the ceiling is only adjudicable when
                // every class is visible with witnesses)
                SqlTypeCensus.classes(40).forEach(c -> System.out
                        .println("[pct-census] class: " + c));
                SqlTypeCensus.allSamples().forEach((cls, ws) ->
                        ws.forEach(w -> System.out.println(
                                "[pct-census] witness: " + cls + " :: "
                                        + w)));
                check(suite, "label lie escaped reconciliation (mismatch)",
                        SqlTypeCensus.mismatchCount(), 0);
                check(suite, "wire adopt-pending grew",
                        SqlTypeCensus.wireAdoptPendingCount(),
                        MAX_ADOPT_PENDING);
                check(suite, "wire divergence grew",
                        SqlTypeCensus.wireDivergeCount(), MAX_WIRE_DIVERGE);
                check(suite, "untyped projection roots grew — a missing"
                                + " rule or an unstamped leaf",
                        SqlTypeCensus.untypedCount(), MAX_UNTYPED);
            }
        };
    }

    private static void check(String suite, String what, long actual,
            long ceiling) {
        if (actual > ceiling) {
            throw new AssertionError("[pct-census] " + suite + ": " + what
                    + ": " + actual + " > " + ceiling + " — "
                    + SqlTypeCensus.summary());
        }
    }
}
