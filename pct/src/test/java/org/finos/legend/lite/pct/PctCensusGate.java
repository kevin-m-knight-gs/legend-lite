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

    // (The per-lane H2 split retired 2026-08-26: every pin below is
    // EQUALITY-0 on BOTH lanes — the lanes converged as the carriers
    // and stamps landed.)

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
    // (probed union shape). 20 -> 1 (§4bZ-U tree-receipt burn,
    // 2026-08-25 — every one of the 20 construction trees captured
    // and mechanism-fixed): property reads over stamped params
    // (foldResolver/mapElemResolver struct-field arms), the accIsList
    // fold rule (probed: the list-boxed lane delivers the acc's own
    // array), mixed-class concatenate to the VARIANT carrier (probed:
    // raw struct concat FIELD-UNIONS and smears class identity — one
    // value one carrier, the hetero-literal doctrine), the dedup
    // typedList door (empty removeDuplicates), and the
    // InstanceProjection elem stamp (the hardcoded-VARCHAR lateral).
    // 1 -> 0 (2026-08-26): the last row was testSimpleProject's EMPTY
    // `values` collection — an empty literal types Nil, so the
    // instance-projection lateral VARCHAR-guessed its element under a
    // StructGet('val'); the element type now comes from the colspec
    // BODY's own declared segment types
    // (InstanceProjection.pathTypesOf). ZERO on both lanes — HARDENED
    // TO EQUALITY: a new untyped root is a regression, witness in the
    // failure message. (ChannelB-context tags on pct witnesses are
    // unreliable — channel A never sets CONTEXT; capture TREES.)
    // (h2's 17 burned with the B3 temporal-text stamps — measured 0 on
    // the post-B3 G7; EQUALITY both lanes.)
    private static final long MAX_UNTYPED = 0;
    // §4bZ-V C ADJUDICATED (2026-08-26, the wire-tree capture method):
    // diverge 78 -> 45 -> 0 and adopt-pending 101 -> 64 -> 0, BOTH
    // HARDENED TO EQUALITY. The kills, each probed on 1.5.0:
    // star-tail label reconciliation (EVERY remaining row lived in a
    // star-bearing frame the old size gate skipped wholesale — labels
    // now adopt computed types through one leading star + k computed
    // tail); Float-declared TDS cells seed DOUBLE literals (the
    // DOUBLE<>DECIMAL(p,s) head-column family — DecimalLit seeds made
    // DuckDB type whole Values columns DECIMAL); scale-0 DecimalLits
    // beyond long are big pure INTEGERS and type HUGEINT, within long
    // they are d-suffixed pure DECIMALS and the renderer CASTS so the
    // wire reads DECIMAL (bare digits read INTEGER; typing the FACT by
    // magnitude instead flipped percentile's carrier dispatch — facts
    // follow the contract, emissions follow the fact); >38-digit
    // fractional literals read DOUBLE (DECIMAL's precision cap);
    // GUID() casts to VARCHAR (pure String contract; bare uuid() wires
    // UUID); repeatString VARCHAR-casts an untyped arg (DuckDB's
    // binder picked the BLOB overload for bare NULL); descending
    // continuous percentile is the NEGATION identity -(qc(-v, p)) —
    // the (1-p) transform diverged in float ULPs from the engine's
    // WITHIN GROUP DESC path (testPercentile_Relation_Window's
    // byte-compare refereed).
    private static final long MAX_WIRE_DIVERGE = 0;
    private static final long MAX_ADOPT_PENDING = 0;
    // THE NULLABILITY LEDGER (§4bZ-V E, 2026-08-26 — §4Z ledger #4):
    // this lane carried 6 literal-NullLit DOUBLE value-frames (the
    // corr/covarPopulation/covarSample PCT family); N1 made a
    // projected literal NULL declare its slot nullable at construction
    // (reconcileLabels), burning them with the corpus lane's 6,472
    // union pads. Residue adjudicated EMPTY — EQUALITY at zero on
    // both pct lanes: a row here is a COMPUTED bottom (a
    // NULL-propagating expression) under a required label.
    private static final long MAX_BOTTOM_MULT = 0;
    // §4bZ-V D (2026-08-26): the wire probe now ADJUDICATES every
    // column. Unknown = a probe that could not judge (zero-output and
    // pivot frames are no-claim by doctrine; the old 8 were all pivot
    // tests) — EQUALITY at zero. Int-or-null settles on VALUE
    // evidence: all 219 lane rows PROVED all-NULL (empty-result PCT
    // fixtures — greatest/least_Empty et al.); a valued column lands
    // in diverge (EQUALITY-0) instead. Ceiling, shape-driven.
    private static final long MAX_WIRE_UNKNOWN = 0;
    private static final long MAX_INT_NULL_EMPTY = 219;
    // §4bZ-V B3+B4 (2026-08-26): the admissible bucket is DELETED with
    // the relation itself — the temporal-text traffic is the
    // TEMPORAL_TEXT carrier, the JSON egress conforms by emission, and
    // a pair matching no named relation now lands in MISMATCH (pinned
    // 0 below) — strictly louder than any ceiling here could be.

    public static Test wrap(String suite, Test t) {
        return new TestSetup(t) {
            @Override
            protected void tearDown() {
                System.out.println("[pct-census] after " + suite + ": "
                        + SqlTypeCensus.summary());
                // the untyped DECOMPOSITION (the corpus lane's census
                // display, brought to the pct lane 2026-08-25 — no
                // silent caps: the ceiling is only adjudicable when
                // every class is visible with witnesses; 40 -> 100 at
                // N0's bottom-mult SHAPE split, §4bZ-V E)
                SqlTypeCensus.classes(100).forEach(c -> System.out
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
                check(suite, "computed NULL under a required-multiplicity"
                                + " label (bottom-mult)",
                        SqlTypeCensus.bottomMultCount(), MAX_BOTTOM_MULT);
                check(suite, "unadjudicated wire probes appeared",
                        SqlTypeCensus.wireUnknownCount(), MAX_WIRE_UNKNOWN);
                check(suite, "proven-empty int-or-null columns grew",
                        SqlTypeCensus.wireIntOrNullEmptyCount(),
                        MAX_INT_NULL_EMPTY);
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
